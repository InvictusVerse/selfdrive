package com.selfdriving.auth;

import java.time.Instant;
import java.util.Set;

/**
 * A user account (without its password hash, which never leaves the repository layer).
 *
 * @param lockedUntil end of a login lock-out, or null
 * @param lastLogin   last successful login, or null
 */
public record User(long id, String username, String fullName, Role role, AccessLevel accessLevel, boolean active,
                   int failedAttempts, Instant lockedUntil, Instant lastLogin, Instant createdAt) {

    public Set<Permission> permissions() {
        return role.permissions(accessLevel);
    }

    public boolean can(Permission permission) {
        return active && permissions().contains(permission);
    }
}
