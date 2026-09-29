package com.selfdriving.service;

import com.selfdriving.auth.AccessLevel;
import com.selfdriving.auth.PasswordHasher;
import com.selfdriving.auth.Role;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.persistence.UserRepository;

/**
 * Demonstration accounts, created only when the database has no users yet. They are meant for
 * trying the app; change the passwords (or delete the accounts) before using it anywhere else.
 */
public final class DemoData {

    /** A demo login. */
    public record Account(String username, String password, String fullName, Role role, AccessLevel level) {
    }

    public static final Account[] ACCOUNTS = {
            new Account("admin", "Admin@2026", "Asha Rao", Role.ADMIN, AccessLevel.FULL),
            new Account("driver", "Driver@2026", "Rahul Menon", Role.DRIVER, AccessLevel.FULL),
            new Account("tech", "Tech@2026", "Priya Nair", Role.TECHNICIAN, AccessLevel.FULL),
    };

    private DemoData() {
    }

    /** @return true if the accounts were created (first start) */
    public static boolean seed(UserRepository users, AuditRepository audit, PasswordHasher hasher) {
        if (users.count() > 0) {
            return false;
        }
        for (Account a : ACCOUNTS) {
            users.create(a.username(), a.fullName(), hasher.hash(a.password().toCharArray()), a.role(), a.level());
        }
        audit.log(null, "system", "DEMO_ACCOUNTS", ACCOUNTS.length + " demo accounts created on first start");
        return true;
    }
}
