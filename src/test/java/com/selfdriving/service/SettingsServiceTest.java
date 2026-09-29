package com.selfdriving.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.auth.AccessLevel;
import com.selfdriving.auth.Role;
import com.selfdriving.persistence.SettingsRepository;

class SettingsServiceTest extends ServiceTestSupport {

    private final SettingsService settings = new SettingsService(new SettingsRepository(db), audit);
    private final Session admin = session(user("admin", "Admin2026", Role.ADMIN, AccessLevel.STANDARD));
    private final Session driver = session(user("driver", "Driver2026", Role.DRIVER, AccessLevel.FULL));

    @Test
    @DisplayName("Defaults until changed; changes are stored, audited and announced")
    void change() {
        assertEquals("100", settings.get(SettingsService.MAX_AUTOPILOT_SPEED));
        assertEquals("2.0.0", settings.get(SettingsService.SOFTWARE_VERSION));
        List<Map<String, String>> seen = new ArrayList<>();
        settings.onChange(seen::add);
        settings.set(admin, SettingsService.MAX_AUTOPILOT_SPEED, " 80 ");
        assertEquals(80, settings.number(SettingsService.MAX_AUTOPILOT_SPEED), 1e-9);
        assertEquals(1, seen.size());
        assertEquals("80", seen.get(0).get(SettingsService.MAX_AUTOPILOT_SPEED));
        assertEquals("SETTING_CHANGED", audit.recent(1).get(0).action());
        assertEquals("Maximum autopilot speed: 100 -> 80", audit.recent(1).get(0).details());
    }

    @Test
    @DisplayName("Drivers cannot change settings")
    void permission() {
        assertThrows(AccessDeniedException.class, () -> settings.set(driver, SettingsService.TRAFFIC, "10"));
    }

    @Test
    @DisplayName("Values are checked against their type and range and stored in a standard form")
    void validation() {
        assertThrows(ValidationException.class, () -> SettingsService.validate(SettingsService.MAX_AUTOPILOT_SPEED, "200"));
        assertThrows(ValidationException.class, () -> SettingsService.validate(SettingsService.MAX_AUTOPILOT_SPEED, "fast"));
        assertThrows(ValidationException.class, () -> SettingsService.validate(SettingsService.EMERGENCY_BRAKING, "yes"));
        assertThrows(ValidationException.class, () -> SettingsService.validate("no.such.key", "1"));
        assertEquals("1.5", SettingsService.validate(SettingsService.FOLLOW_TIME_GAP, "1.50"));
        assertEquals("2.0", SettingsService.validate(SettingsService.FOLLOW_TIME_GAP, "2"));
        assertEquals("0.25", SettingsService.validate(SettingsService.AEB_SENSITIVITY, "0.25"));
        assertEquals("false", SettingsService.validate(SettingsService.EMERGENCY_BRAKING, "FALSE"));
        assertEquals("60", SettingsService.validate(SettingsService.MAX_AUTOPILOT_SPEED, "59.6"));
    }
}
