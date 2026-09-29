package com.selfdriving.service;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.selfdriving.alerts.Alert;
import com.selfdriving.auth.PasswordHasher;
import com.selfdriving.persistence.AlertRepository;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.persistence.Database;
import com.selfdriving.persistence.IssueRepository;
import com.selfdriving.persistence.MaintenanceRepository;
import com.selfdriving.persistence.SettingsRepository;
import com.selfdriving.persistence.TripRepository;
import com.selfdriving.persistence.UpdateRepository;
import com.selfdriving.persistence.UserRepository;
import com.selfdriving.simulation.Simulation;

/**
 * Wires the database, repositories and services together and connects them to the running
 * simulation. Screens get everything they need from here.
 */
public final class ApplicationContext implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ApplicationContext.class.getName());

    /** System property that fixes the number of traffic vehicles (developer tool), ignoring the setting. */
    public static final String TRAFFIC_PROPERTY = "selfdrive.traffic";

    private final Database database;
    private final Clock clock;
    private final Simulation simulation;
    private final AuditRepository audit;
    private final AuthService auth;
    private final UserService users;
    private final SettingsService settings;
    private final HistoryService history;
    private final DbWriter writer = new DbWriter();
    private final Recorder recorder;
    private final UserRepository userRepository;
    private final PasswordHasher hasher;
    private final MaintenanceService maintenance;
    private final UpdateService updates;
    private final DashboardService dashboard;
    private volatile Session session;

    public ApplicationContext(Database database, Simulation simulation, Clock clock, PasswordHasher hasher) {
        this(database, simulation, clock, hasher, Duration.ZERO);
    }

    /**
     * @param updateStep pause between software update progress steps (zero: instant, for tests)
     */
    public ApplicationContext(Database database, Simulation simulation, Clock clock, PasswordHasher hasher,
                              Duration updateStep) {
        this.database = database;
        this.clock = clock;
        this.simulation = simulation;
        this.hasher = hasher;
        this.userRepository = new UserRepository(database);
        TripRepository trips = new TripRepository(database);
        AlertRepository alerts = new AlertRepository(database);
        this.audit = new AuditRepository(database);
        if (DemoData.seed(userRepository, audit, hasher)) {
            LOG.log(Level.INFO, "Created the demo accounts");
        }
        trips.closeUnfinished(clock.instant());

        this.auth = new AuthService(userRepository, audit, hasher, clock);
        this.users = new UserService(userRepository, audit, hasher);
        this.settings = new SettingsService(new SettingsRepository(database), audit);
        this.history = new HistoryService(trips, alerts, audit);
        this.recorder = new Recorder(trips, alerts, writer, clock, () -> session);
        IssueRepository issues = new IssueRepository(database);
        MaintenanceRepository maintenanceLog = new MaintenanceRepository(database);
        UpdateRepository updateRepository = new UpdateRepository(database);
        this.maintenance = new MaintenanceService(simulation, issues, maintenanceLog, audit, clock);
        this.updates = new UpdateService(updateRepository, settings, audit, simulation, clock, updateStep);
        this.dashboard = new DashboardService(userRepository, trips, alerts, audit, issues, maintenanceLog,
                updateRepository, settings, clock);

        auth.onSecurityAlert(message -> simulation.raiseAlert(Alert.Severity.WARNING, Alert.Category.SECURITY,
                message, "Login"));
        simulation.addListener(recorder);
        settings.onChange(this::apply);
        apply(settings.all());
    }

    /**
     * Demo accounts that still have their published password (shown as a hint on the login
     * screen until changed). Slow: one full hash per account; call in the background.
     */
    public java.util.List<DemoData.Account> unchangedDemoAccounts() {
        java.util.List<DemoData.Account> result = new java.util.ArrayList<>();
        for (DemoData.Account a : DemoData.ACCOUNTS) {
            userRepository.findByUsername(a.username()).filter(com.selfdriving.auth.User::active).ifPresent(u -> {
                if (hasher.verify(a.password().toCharArray(), userRepository.passwordHash(u.id()))) {
                    result.add(a);
                }
            });
        }
        return result;
    }

    /** The app's database in its data folder. */
    public static ApplicationContext open(Simulation simulation) {
        return new ApplicationContext(Database.openDefault(), simulation, Clock.systemUTC(), new PasswordHasher(),
                Duration.ofMillis(250));
    }

    /** Sends the stored settings to the car. */
    private void apply(Map<String, String> values) {
        double maxKmh = Double.parseDouble(values.get(SettingsService.MAX_AUTOPILOT_SPEED));
        double gap = Double.parseDouble(values.get(SettingsService.FOLLOW_TIME_GAP));
        boolean aeb = Boolean.parseBoolean(values.get(SettingsService.EMERGENCY_BRAKING));
        double reaction = Double.parseDouble(values.get(SettingsService.AEB_SENSITIVITY));
        int traffic = Integer.getInteger(TRAFFIC_PROPERTY, Integer.parseInt(values.get(SettingsService.TRAFFIC)));
        simulation.submit(sim -> {
            sim.setMaxAutopilotSpeed(maxKmh / 3.6);
            sim.setFollowTimeGap(gap);
            sim.setEmergencyBrakingReaction(reaction);
            if (sim.latest().settings().emergencyBrakingEnabled() != aeb) {
                sim.setEmergencyBrakingEnabled(aeb);
            }
            if (sim.trafficCount() != traffic) {
                sim.setTrafficCount(traffic);
            }
        });
    }

    // ---- Session ----------------------------------------------------------------------------

    /** Checks the password; on success this becomes the current session. */
    public AuthService.Result login(String username, char[] password) {
        AuthService.Result result = auth.login(username, password);
        if (result.ok()) {
            session = result.session();
        }
        return result;
    }

    /** Ends the session: any trip is cancelled and the car is secured. */
    public void logout() {
        Session ending = session;
        if (ending == null) {
            return;
        }
        simulation.submit(sim -> {
            sim.disengageAutopilot();
            sim.clearRoute();
        });
        simulation.driverInput().releaseAll();
        session = null;
        auth.logout(ending);
    }

    /** Who is logged in, or null. */
    public Session session() {
        return session;
    }

    // ---- Database console -------------------------------------------------------------------

    /**
     * Opens H2's web console in the browser, connected to this database (full administrators
     * only). The console listens on this PC only (H2's default) and closes when the browser
     * session disconnects.
     */
    public void openDatabaseConsole(Session who) {
        who.require(com.selfdriving.auth.Permission.OPEN_DATABASE_CONSOLE);
        audit.log(who.userId(), who.user().username(), "DATABASE_CONSOLE", "Opened the H2 web console");
        Thread t = new Thread(() -> {
            try (java.sql.Connection c = database.connect()) {
                org.h2.tools.Server.startWebServer(c);
            } catch (java.sql.SQLException e) {
                LOG.log(Level.WARNING, "Database console failed", e);
            }
        }, "database-console");
        t.setDaemon(true);
        t.start();
    }

    // ---- Services ---------------------------------------------------------------------------

    public MaintenanceService maintenance() {
        return maintenance;
    }

    public UpdateService updates() {
        return updates;
    }

    public DashboardService dashboard() {
        return dashboard;
    }

    public Simulation simulation() {
        return simulation;
    }

    public UserService users() {
        return users;
    }

    public SettingsService settings() {
        return settings;
    }

    public HistoryService history() {
        return history;
    }

    public Recorder recorder() {
        return recorder;
    }

    public AuditRepository audit() {
        return audit;
    }

    public Database database() {
        return database;
    }

    public Clock clock() {
        return clock;
    }

    /** Waits for queued database writes (tests). */
    public void flush() {
        writer.flush();
    }

    @Override
    public void close() {
        simulation.removeListener(recorder);
        updates.close();
        writer.close();
        database.close();
    }
}
