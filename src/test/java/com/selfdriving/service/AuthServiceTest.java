package com.selfdriving.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.auth.AccessLevel;
import com.selfdriving.auth.Role;
import com.selfdriving.persistence.AuditRepository;

class AuthServiceTest extends ServiceTestSupport {

    private final List<String> securityAlerts = new ArrayList<>();
    private final AuthService auth = new AuthService(users, audit, hasher, clock);
    private final long driver = user("driver", "Driver2026", Role.DRIVER, AccessLevel.FULL);

    {
        auth.onSecurityAlert(securityAlerts::add);
    }

    private AuthService.Result login(String name, String password) {
        return auth.login(name, password.toCharArray());
    }

    @Test
    @DisplayName("The right password signs in (username in any case) and records the sign-in")
    void success() {
        AuthService.Result r = login("DRIVER", "Driver2026");
        assertTrue(r.ok());
        assertEquals(driver, r.session().userId());
        assertNotNull(users.find(driver).orElseThrow().lastLogin());
        assertEquals("LOGIN", audit.recent(1).get(0).action());
    }

    @Test
    @DisplayName("A wrong password and an unknown user get the same answer")
    void sameMessage() {
        AuthService.Result wrong = login("driver", "nope");
        AuthService.Result unknown = login("nobody", "nope");
        assertFalse(wrong.ok());
        assertFalse(unknown.ok());
        assertEquals(wrong.message(), unknown.message());
    }

    @Test
    @DisplayName("Five wrong passwords lock the account for a minute, even for the right password; then it opens")
    void lockOut() {
        for (int i = 0; i < AuthService.MAX_ATTEMPTS; i++) {
            assertFalse(login("driver", "wrong" + i).ok());
        }
        assertTrue(users.find(driver).orElseThrow().lockedUntil() != null);
        AuthService.Result locked = login("driver", "Driver2026");
        assertFalse(locked.ok());
        assertTrue(locked.message().startsWith("Too many wrong passwords"), locked.message());
        assertTrue(securityAlerts.stream().anyMatch(a -> a.contains("locked")), securityAlerts.toString());
        assertTrue(audit.recent(20).stream().map(AuditRepository.Entry::action).anyMatch("ACCOUNT_LOCKED"::equals));

        clock.advance(AuthService.LOCK_TIME.plus(Duration.ofSeconds(1)));
        assertTrue(login("driver", "Driver2026").ok(), "unlocked after the lock time");
        assertEquals(0, users.find(driver).orElseThrow().failedAttempts());
    }

    @Test
    @DisplayName("Three wrong passwords in a row raise a security alert; a success resets the count")
    void repeatedFailures() {
        login("driver", "a");
        login("driver", "b");
        assertTrue(securityAlerts.isEmpty());
        login("driver", "c");
        assertEquals(1, securityAlerts.size());
        assertTrue(login("driver", "Driver2026").ok());
        login("driver", "d");
        assertEquals(1, users.find(driver).orElseThrow().failedAttempts());
    }

    @Test
    @DisplayName("A deactivated account cannot sign in, even with the right password")
    void deactivated() {
        users.update(driver, "Driver", Role.DRIVER, AccessLevel.FULL, false);
        AuthService.Result r = login("driver", "Driver2026");
        assertFalse(r.ok());
        assertTrue(r.message().contains("deactivated"));
    }

    @Test
    @DisplayName("The password array is wiped after the check")
    void wipesPassword() {
        char[] password = "Driver2026".toCharArray();
        auth.login("driver", password);
        for (char c : password) {
            assertEquals('\0', c);
        }
    }
}
