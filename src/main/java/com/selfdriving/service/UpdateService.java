package com.selfdriving.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.selfdriving.alerts.Alert;
import com.selfdriving.auth.Permission;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.persistence.UpdateRepository;
import com.selfdriving.persistence.UpdateRepository.Status;
import com.selfdriving.persistence.UpdateRepository.Update;
import com.selfdriving.simulation.Simulation;

/**
 * Over-the-air software updates.
 *
 * <p>An update is a package of setting changes with a SHA-256 checksum. Installing it:
 * AVAILABLE → DOWNLOADING (the package arrives in chunks) → the checksum is checked →
 * INSTALLING (the car is held in Park) → COMPLETED, and the software version changes. A package
 * whose checksum does not match is FAILED and then ROLLED_BACK: nothing is changed. A completed
 * update can be rolled back, which restores the values it replaced.
 *
 * <p>Rules: only while the car is parked with nobody driving; one install at a time; versions in
 * order (the oldest available first).
 */
public final class UpdateService implements AutoCloseable {

    /** An update the manufacturer offers. */
    record Package(String version, String notes, Map<String, String> changes, boolean damagedInTransit) {

        byte[] content() {
            StringBuilder sb = new StringBuilder("selfdrive-update ").append(version).append('\n').append(notes);
            changes.forEach((k, v) -> sb.append('\n').append(k).append('=').append(v));
            // Pad to a realistic size so the download has something to show.
            byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[256 * 1024];
            System.arraycopy(head, 0, body, 0, head.length);
            for (int i = head.length; i < body.length; i++) {
                body[i] = (byte) (i * 31 + version.hashCode());
            }
            return body;
        }

        String checksum() {
            return sha256(content());
        }
    }

    /** What the manufacturer's server offers (in a real car this would be fetched online). */
    static final List<Package> CATALOGUE = List.of(
            new Package("2.1.0", "Smoother following in stop-and-go traffic: the autopilot keeps a 1.5 s gap "
                    + "(was 1.2 s)", Map.of(SettingsService.FOLLOW_TIME_GAP, "1.5"), false),
            new Package("2.2.0", "Emergency braking starts 0.1 s earlier (0.35 s reaction allowance) after "
                    + "wet-road testing", Map.of(SettingsService.AEB_SENSITIVITY, "0.35"), false),
            new Package("2.3.0", "Lane-change planner refinements and a lower autopilot limit of 90 km/h in the "
                    + "city", Map.of(SettingsService.MAX_AUTOPILOT_SPEED, "90"), true));

    private static final int STEPS = 20;

    private final UpdateRepository updates;
    private final SettingsService settings;
    private final AuditRepository audit;
    private final Simulation simulation;
    private final Clock clock;
    private final Duration step;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "software-update");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param step pause between progress steps (a few seconds in all for the app; zero in tests)
     */
    public UpdateService(UpdateRepository updates, SettingsService settings, AuditRepository audit,
                         Simulation simulation, Clock clock, Duration step) {
        this.updates = updates;
        this.settings = settings;
        this.audit = audit;
        this.simulation = simulation;
        this.clock = clock;
        this.step = step;
    }

    /** Called (on the update thread) whenever an update's progress or status changes. */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    public String currentVersion() {
        return settings.get(SettingsService.SOFTWARE_VERSION);
    }

    /** Asks the server for updates newer than the installed version; returns all updates known. */
    public List<Update> check(Session session) {
        session.require(Permission.DEPLOY_UPDATES);
        for (Package p : CATALOGUE) {
            if (compare(p.version(), currentVersion()) > 0) {
                updates.offer(p.version(), p.notes());
            }
        }
        return list(session);
    }

    public List<Update> list(Session session) {
        session.require(Permission.DEPLOY_UPDATES);
        List<Update> all = new ArrayList<>(updates.all());
        all.sort(Comparator.comparing(Update::version, UpdateService::compare));
        return all;
    }

    public boolean isInstalling() {
        return busy.get();
    }

    /** Checks the rules, then installs in the background. */
    public void start(Session session, long id) {
        Update u = prepare(session, id);
        executor.execute(() -> {
            try {
                install(session, u);
            } finally {
                busy.set(false);
            }
        });
    }

    /** Checks the rules and installs, returning when finished (tests). */
    public Update installNow(Session session, long id) {
        Update u = prepare(session, id);
        try {
            install(session, u);
        } finally {
            busy.set(false);
        }
        return updates.find(id).orElseThrow();
    }

    private Update prepare(Session session, long id) {
        session.require(Permission.DEPLOY_UPDATES);
        Update u = updates.find(id).orElseThrow(() -> new ValidationException("No such update"));
        if (u.status() != Status.AVAILABLE && u.status() != Status.ROLLED_BACK) {
            throw new ValidationException("Version " + u.version() + " is "
                    + u.status().name().toLowerCase().replace('_', ' '));
        }
        if (compare(u.version(), currentVersion()) <= 0) {
            throw new ValidationException("Version " + u.version() + " is not newer than the installed "
                    + currentVersion());
        }
        for (Update other : list(session)) {
            if (compare(other.version(), currentVersion()) > 0 && compare(other.version(), u.version()) < 0
                    && other.status() != Status.COMPLETED) {
                throw new ValidationException("Install " + other.version() + " first: updates go in order");
            }
        }
        if (!OnSimulation.call(simulation, Simulation::isParkedSafely)) {
            throw new ValidationException("Stop the car and select Park before installing an update");
        }
        if (!busy.compareAndSet(false, true)) {
            throw new ValidationException("Another update is being installed");
        }
        return u;
    }

    private void install(Session session, Update u) {
        Package p = CATALOGUE.stream().filter(c -> c.version().equals(u.version())).findFirst()
                .orElseThrow(() -> new ValidationException("The server no longer offers " + u.version()));
        audit.log(session.userId(), session.user().username(), "UPDATE_STARTED", u.version());
        updates.start(u.id(), session.userId(), clock.instant());
        changed();

        // Download in chunks; a package damaged on the way arrives with a changed byte.
        byte[] source = p.content();
        byte[] received = new byte[source.length];
        int chunk = source.length / STEPS;
        for (int i = 0; i < STEPS; i++) {
            int from = i * chunk;
            int to = i == STEPS - 1 ? source.length : from + chunk;
            System.arraycopy(source, from, received, from, to - from);
            pause();
            updates.progress(u.id(), Status.DOWNLOADING, (i + 1) * 100 / STEPS);
            changed();
        }
        // The first download of this package is corrupted on the way; downloading again works.
        boolean retry = u.detail() != null && u.detail().startsWith("Checksum");
        if (p.damagedInTransit() && !retry) {
            received[received.length / 2] ^= 0x5A;
        }
        String got = sha256(received);
        if (!got.equals(p.checksum())) {
            String detail = "Checksum does not match (expected " + p.checksum().substring(0, 12) + "\u2026, got "
                    + got.substring(0, 12) + "\u2026). Nothing was installed";
            updates.finish(u.id(), Status.FAILED, detail, null, clock.instant());
            changed();
            pause();
            updates.finish(u.id(), Status.ROLLED_BACK, detail + "; " + currentVersion() + " kept", null,
                    clock.instant());
            audit.log(session.userId(), session.user().username(), "UPDATE_FAILED", u.version() + ": checksum");
            simulation.raiseAlert(Alert.Severity.WARNING, Alert.Category.UPDATE, "Update " + u.version()
                    + " failed its checksum and was rolled back. " + currentVersion() + " is still installed", "Updates");
            changed();
            return;
        }

        // Install with the car held in Park.
        OnSimulation.run(simulation, s -> s.setSoftwareUpdating(true));
        try {
            Map<String, String> previous = new LinkedHashMap<>();
            previous.put(SettingsService.SOFTWARE_VERSION, currentVersion());
            for (String key : p.changes().keySet()) {
                previous.put(key, settings.get(key));
            }
            for (int i = 0; i < STEPS; i++) {
                pause();
                updates.progress(u.id(), Status.INSTALLING, (i + 1) * 100 / STEPS);
                changed();
            }
            p.changes().forEach((k, v) -> settings.setBySystem(k, v, "Update " + u.version()));
            settings.setBySystem(SettingsService.SOFTWARE_VERSION, u.version(), "Update " + u.version());
            updates.finish(u.id(), Status.COMPLETED, describe(p.changes()), encode(previous), clock.instant());
            audit.log(session.userId(), session.user().username(), "UPDATE_COMPLETED", u.version());
            simulation.raiseAlert(Alert.Severity.INFO, Alert.Category.UPDATE, "Software " + u.version()
                    + " installed: " + p.notes(), "Updates");
        } finally {
            OnSimulation.run(simulation, s -> s.setSoftwareUpdating(false));
            changed();
        }
    }

    /** Undoes the most recent completed update. */
    public Update rollBack(Session session, long id) {
        session.require(Permission.DEPLOY_UPDATES);
        Update u = updates.find(id).orElseThrow(() -> new ValidationException("No such update"));
        if (u.status() != Status.COMPLETED) {
            throw new ValidationException("Only a completed update can be rolled back");
        }
        if (!u.version().equals(currentVersion())) {
            throw new ValidationException("Roll back " + currentVersion() + " first: the newest update goes first");
        }
        if (busy.get()) {
            throw new ValidationException("An update is being installed");
        }
        Map<String, String> previous = decode(u.previousValues());
        previous.forEach((k, v) -> settings.setBySystem(k, v, "Roll back of " + u.version()));
        updates.finish(u.id(), Status.ROLLED_BACK, "Rolled back by " + session.user().fullName() + "; restored "
                + describe(previous), u.previousValues(), clock.instant());
        audit.log(session.userId(), session.user().username(), "UPDATE_ROLLED_BACK", u.version());
        simulation.raiseAlert(Alert.Severity.INFO, Alert.Category.UPDATE, "Update " + u.version()
                + " rolled back to " + currentVersion(), "Updates");
        changed();
        return updates.find(id).orElseThrow();
    }

    private void changed() {
        listeners.forEach(Runnable::run);
    }

    private void pause() {
        if (!step.isZero()) {
            try {
                Thread.sleep(step.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String describe(Map<String, String> values) {
        List<String> parts = new ArrayList<>();
        values.forEach((k, v) -> parts.add((k.equals(SettingsService.SOFTWARE_VERSION) ? "software version"
                : SettingsService.definition(k).label().toLowerCase()) + " " + v));
        return String.join(", ", parts);
    }

    static String encode(Map<String, String> values) {
        List<String> parts = new ArrayList<>();
        values.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join(";", parts);
    }

    static Map<String, String> decode(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text != null && !text.isBlank()) {
            for (String part : text.split(";")) {
                int eq = part.indexOf('=');
                values.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return values;
    }

    /** Compares dotted version numbers numerically (2.10.0 is newer than 2.9.0). */
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
