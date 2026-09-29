package com.selfdriving.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.alerts.Alert;
import com.selfdriving.auth.AccessLevel;
import com.selfdriving.auth.Role;
import com.selfdriving.auth.User;

/** The schema scripts and every repository against a real (in-memory) H2 database. */
class DatabaseTest {

    private final String name = "t" + UUID.randomUUID().toString().replace("-", "");
    private final Database db = Database.inMemory(name);
    private final UserRepository users = new UserRepository(db);
    private final Instant now = Instant.parse("2026-09-29T10:00:00Z");

    @Test
    @DisplayName("Migrations run once, are recorded, and running them again changes nothing")
    void migrations() throws Exception {
        assertEquals(List.of("V1__users_settings_alerts_trips.sql", "V2__maintenance_updates_tests.sql"), applied(db));
        Database again = Database.inMemory(name); // same database, opened a second time
        assertEquals(2, applied(again).size());
        try (Connection c = db.connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
            r.next();
            assertEquals(11, r.getInt(1), "10 tables + schema_version");
        }
    }

    @Test
    @DisplayName("Scripts are split into statements; comments are dropped")
    void splitsStatements() {
        List<String> sql = Database.statements("-- heading\nCREATE TABLE a (x INT); -- note\n\nINSERT INTO a\n VALUES (1);\n");
        assertEquals(List.of("CREATE TABLE a (x INT)", "INSERT INTO a\n VALUES (1)"), sql);
    }

    @Test
    @DisplayName("Users: create, find ignoring case, update, lock-out fields, unique names, delete")
    void users() {
        long id = users.create("Asha", "Asha Rao", "hash1", Role.ADMIN, AccessLevel.FULL);
        User u = users.findByUsername("asha").orElseThrow();
        assertEquals(id, u.id());
        assertEquals(Role.ADMIN, u.role());
        assertTrue(u.active());
        assertEquals("hash1", users.passwordHash(id));
        assertEquals(1, users.activeFullAdmins());

        users.update(id, "Asha R.", Role.DRIVER, AccessLevel.STANDARD, false);
        u = users.find(id).orElseThrow();
        assertEquals("Asha R.", u.fullName());
        assertFalse(u.active());
        assertEquals(0, users.activeFullAdmins());

        users.recordFailure(id, 3, now.plusSeconds(60));
        u = users.find(id).orElseThrow();
        assertEquals(3, u.failedAttempts());
        assertEquals(now.plusSeconds(60), u.lockedUntil());
        users.setPassword(id, "hash2");
        u = users.find(id).orElseThrow();
        assertEquals(0, u.failedAttempts(), "a new password clears the lock-out");
        assertNull(u.lockedUntil());

        assertThrows(DataException.class, () -> users.create("asha", "Other", "h", Role.DRIVER, AccessLevel.FULL),
                "usernames are unique");
        users.delete(id);
        assertEquals(0, users.count());
    }

    @Test
    @DisplayName("Settings are stored and overwritten")
    void settings() {
        SettingsRepository settings = new SettingsRepository(db);
        assertTrue(settings.get("a").isEmpty());
        settings.put("a", "1", null);
        settings.put("a", "2", null);
        assertEquals("2", settings.get("a").orElseThrow());
        assertEquals(1, settings.all().size());
    }

    @Test
    @DisplayName("Alerts: saved, filtered by severity and category, acknowledged once with the user's name")
    void alerts() {
        long admin = users.create("admin", "Admin", "h", Role.ADMIN, AccessLevel.FULL);
        AlertRepository alerts = new AlertRepository(db);
        long info = alerts.insert(alert(Alert.Severity.INFO, Alert.Category.NAVIGATION), now);
        long critical = alerts.insert(alert(Alert.Severity.CRITICAL, Alert.Category.COLLISION), now.plusSeconds(1));
        alerts.insert(alert(Alert.Severity.WARNING, Alert.Category.SECURITY), now.plusSeconds(2));

        assertEquals(3, alerts.recent(10, Alert.Severity.INFO, null).size());
        assertEquals(2, alerts.recent(10, Alert.Severity.WARNING, null).size());
        assertEquals(List.of(critical), alerts.recent(10, Alert.Severity.CRITICAL, null).stream()
                .map(AlertRepository.Stored::id).toList());
        assertEquals(1, alerts.recent(10, Alert.Severity.INFO, Alert.Category.SECURITY).size());

        alerts.acknowledge(critical, admin, now.plusSeconds(5));
        alerts.acknowledge(critical, null, now.plusSeconds(9)); // already acknowledged: unchanged
        AlertRepository.Stored stored = alerts.recent(10, Alert.Severity.CRITICAL, null).get(0);
        assertEquals("admin", stored.acknowledgedBy());
        assertEquals(now.plusSeconds(5), stored.acknowledgedAt());
        assertNull(alerts.recent(10, Alert.Severity.INFO, Alert.Category.NAVIGATION).get(0).acknowledgedAt());
        assertEquals(info, alerts.recent(10, Alert.Severity.INFO, null).get(2).id(), "newest first");

        int[] counts = alerts.countsSince(now);
        assertEquals(1, counts[0]);
        assertEquals(1, counts[1]);
        assertEquals(1, counts[2]);
    }

    @Test
    @DisplayName("Trips: started, finished, listed per driver, unfinished ones closed as cancelled")
    void trips() {
        long driver = users.create("driver", "Rahul Menon", "h", Role.DRIVER, AccessLevel.FULL);
        long other = users.create("other", "Other Driver", "h", Role.DRIVER, AccessLevel.FULL);
        TripRepository trips = new TripRepository(db);
        long a = trips.start(driver, "MG Road", "Cubbon Park", 2400, 300, now);
        trips.finish(a, 2450, 320, 0.36, 0.9, "ARRIVED", now.plusSeconds(320));
        trips.start(other, "Brigade Road", "MG Road", 900, 120, now.plusSeconds(10));

        List<TripRepository.Trip> mine = trips.forDriver(driver, 10);
        assertEquals(1, mine.size());
        TripRepository.Trip t = mine.get(0);
        assertEquals("Rahul Menon", t.driverName());
        assertEquals(2450, t.drivenM(), 1e-9);
        assertEquals(0.9, t.autopilotShare(), 1e-9);
        assertEquals("ARRIVED", t.status());

        trips.closeUnfinished(now.plusSeconds(600));
        List<TripRepository.Trip> all = trips.all(10);
        assertEquals(2, all.size());
        assertEquals("CANCELLED", all.get(0).status());
        assertNull(all.get(0).drivenM());

        users.delete(driver);
        assertNull(trips.all(10).get(1).driverId(), "trips stay when the driver's account is deleted");
    }

    @Test
    @DisplayName("Audit log: newest first, long details cut to fit")
    void audit() {
        AuditRepository audit = new AuditRepository(db);
        audit.log(null, "system", "FIRST", null);
        audit.log(null, "system", "SECOND", "x".repeat(400));
        List<AuditRepository.Entry> entries = audit.recent(10);
        assertEquals("SECOND", entries.get(0).action());
        assertEquals(300, entries.get(0).details().length());
        assertTrue(entries.get(0).at().isAfter(Instant.now().minus(1, ChronoUnit.HOURS)));
    }

    private static Alert alert(Alert.Severity severity, Alert.Category category) {
        return new Alert(1, 0, severity, category, "Test " + category, "Test", false);
    }

    private static List<String> applied(Database db) throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT script FROM schema_version ORDER BY script")) {
            List<String> result = new java.util.ArrayList<>();
            while (r.next()) {
                result.add(r.getString(1));
            }
            return result;
        }
    }
}
