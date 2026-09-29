package com.selfdriving.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.auth.AccessLevel;
import com.selfdriving.auth.Role;
import com.selfdriving.auth.User;

class UserServiceTest extends ServiceTestSupport {

    private final UserService service = new UserService(users, audit, hasher);
    private final long adminId = user("admin", "Admin2026", Role.ADMIN, AccessLevel.FULL);
    private final long driverId = user("driver", "Driver2026", Role.DRIVER, AccessLevel.FULL);
    private final Session admin = session(adminId);
    private final Session driver = session(driverId);

    @Test
    @DisplayName("Only users with the manage-users permission can list or change accounts")
    void permissions() {
        assertThrows(AccessDeniedException.class, () -> service.list(driver));
        assertThrows(AccessDeniedException.class, () -> service.create(driver, "x1234", "X", "Passw0rd1".toCharArray(),
                Role.ADMIN, AccessLevel.FULL));
        assertThrows(AccessDeniedException.class, () -> service.delete(driver, adminId));
        assertEquals(2, service.list(admin).size());
    }

    @Test
    @DisplayName("Creating: usernames are checked and unique regardless of case; passwords follow the rules")
    void create() {
        User u = service.create(admin, "priya.n", "Priya Nair", "Tech2026x".toCharArray(), Role.TECHNICIAN,
                AccessLevel.STANDARD);
        assertEquals(Role.TECHNICIAN, u.role());
        assertTrue(hasher.verify("Tech2026x".toCharArray(), users.passwordHash(u.id())));
        assertThrows(ValidationException.class, () -> service.create(admin, "PRIYA.N", "Other", "Tech2026x".toCharArray(),
                Role.DRIVER, AccessLevel.FULL));
        assertThrows(ValidationException.class, () -> service.create(admin, "a b", "Name", "Tech2026x".toCharArray(),
                Role.DRIVER, AccessLevel.FULL));
        assertThrows(ValidationException.class, () -> service.create(admin, "newuser", "Name", "short1".toCharArray(),
                Role.DRIVER, AccessLevel.FULL));
        assertThrows(ValidationException.class, () -> service.create(admin, "newuser", " ", "Tech2026x".toCharArray(),
                Role.DRIVER, AccessLevel.FULL));
        assertEquals("USER_CREATED", audit.recent(1).get(0).action());
    }

    @Test
    @DisplayName("The last full administrator cannot be removed, demoted or deactivated; nor can admins demote themselves")
    void lastAdmin() {
        assertThrows(ValidationException.class, () -> service.update(admin, adminId, "Admin", Role.DRIVER,
                AccessLevel.FULL, true), "own access");
        assertThrows(ValidationException.class, () -> service.delete(admin, adminId), "own account");

        long second = user("admin2", "Admin2026", Role.ADMIN, AccessLevel.FULL);
        Session other = session(second);
        assertDoesNotThrow(() -> service.update(other, adminId, "Admin", Role.ADMIN, AccessLevel.STANDARD, true));
        // Now 'admin2' is the only full admin: 'admin' (standard) can still manage users but cannot remove it.
        Session standardAdmin = session(adminId);
        assertThrows(ValidationException.class, () -> service.delete(standardAdmin, second));
        assertThrows(ValidationException.class, () -> service.update(standardAdmin, second, "A2", Role.ADMIN,
                AccessLevel.FULL, false));
        assertDoesNotThrow(() -> service.delete(standardAdmin, driverId));
    }

    @Test
    @DisplayName("Resetting a password unlocks the account; changing one's own needs the current password")
    void passwords() {
        users.recordFailure(driverId, 0, clock.instant().plusSeconds(60));
        service.resetPassword(admin, driverId, "Fresh2026".toCharArray());
        assertTrue(hasher.verify("Fresh2026".toCharArray(), users.passwordHash(driverId)));
        assertEquals(null, users.find(driverId).orElseThrow().lockedUntil());

        assertThrows(ValidationException.class, () -> service.changeOwnPassword(driver, "wrong".toCharArray(),
                "Other2026".toCharArray()));
        assertThrows(ValidationException.class, () -> service.changeOwnPassword(driver, "Fresh2026".toCharArray(),
                "weak".toCharArray()));
        service.changeOwnPassword(driver, "Fresh2026".toCharArray(), "Other2026".toCharArray());
        assertTrue(hasher.verify("Other2026".toCharArray(), users.passwordHash(driverId)));
        assertFalse(hasher.verify("Fresh2026".toCharArray(), users.passwordHash(driverId)));
    }
}
