package com.selfdriving.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(1000);

    @Test
    @DisplayName("A hash verifies the right password only, and each hash has its own salt")
    void hashAndVerify() {
        String a = hasher.hash("Secret123".toCharArray());
        String b = hasher.hash("Secret123".toCharArray());
        assertTrue(a.startsWith("pbkdf2-sha256$1000$"));
        assertNotEquals(a, b, "random salt");
        assertTrue(hasher.verify("Secret123".toCharArray(), a));
        assertTrue(hasher.verify("Secret123".toCharArray(), b));
        assertFalse(hasher.verify("secret123".toCharArray(), a));
        assertFalse(hasher.verify("".toCharArray(), a));
    }

    @Test
    @DisplayName("The iteration count is read from the stored hash, so it can be raised later")
    void iterationsStoredPerHash() {
        String old = new PasswordHasher(500).hash("Secret123".toCharArray());
        assertTrue(new PasswordHasher(2000).verify("Secret123".toCharArray(), old));
    }

    @Test
    @DisplayName("The default strength is 600 000 iterations")
    void defaultStrength() {
        assertEquals(600_000, PasswordHasher.DEFAULT_ITERATIONS);
    }

    @Test
    @DisplayName("Damaged or foreign hashes never verify")
    void malformed() {
        for (String bad : new String[] {null, "", "plain", "md5$1$a$b", "pbkdf2-sha256$x$a$b", "pbkdf2-sha256$10$!!$??"}) {
            assertFalse(hasher.verify("Secret123".toCharArray(), bad), String.valueOf(bad));
        }
    }

    @Test
    @DisplayName("Password rules: length, letters and digits, not the username")
    void rules() {
        assertNotNull(PasswordHasher.problem("asha", "Ab1".toCharArray()));
        assertNotNull(PasswordHasher.problem("asha", "abcdefgh".toCharArray()));
        assertNotNull(PasswordHasher.problem("asha", "12345678".toCharArray()));
        assertNotNull(PasswordHasher.problem("asha123x", "ASHA123X".toCharArray()));
        assertNull(PasswordHasher.problem("asha", "Drive2026".toCharArray()));
    }

    @Test
    @DisplayName("Wiping clears the password characters")
    void wipe() {
        char[] p = "Secret123".toCharArray();
        PasswordHasher.wipe(p);
        assertArrayEquals(new char[9], p);
    }

    @Test
    @DisplayName("Roles: full access adds to standard access; inactive users can do nothing")
    void roles() {
        for (Role r : Role.values()) {
            assertTrue(r.permissions(AccessLevel.FULL).containsAll(r.permissions(AccessLevel.STANDARD)));
            assertTrue(r.permissions(AccessLevel.FULL).size() > r.permissions(AccessLevel.STANDARD).size());
        }
        assertTrue(Role.ADMIN.permissions(AccessLevel.STANDARD).contains(Permission.MANAGE_USERS));
        assertFalse(Role.DRIVER.permissions(AccessLevel.FULL).contains(Permission.MANAGE_USERS));
        assertFalse(Role.TECHNICIAN.permissions(AccessLevel.STANDARD).contains(Permission.DRIVE));
        assertTrue(Role.TECHNICIAN.permissions(AccessLevel.FULL).contains(Permission.INJECT_FAULTS));
        User inactive = new User(1, "x", "X", Role.ADMIN, AccessLevel.FULL, false, 0, null, null, null);
        assertFalse(inactive.can(Permission.MANAGE_USERS));
    }
}
