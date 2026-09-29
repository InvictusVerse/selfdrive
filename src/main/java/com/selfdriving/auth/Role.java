package com.selfdriving.auth;

import java.util.EnumSet;
import java.util.Set;

/** What a user is. Each role has a fixed set of permissions; FULL access adds a few more. */
public enum Role {

    ADMIN("Admin",
            EnumSet.of(Permission.MANAGE_USERS, Permission.EDIT_SETTINGS, Permission.DEPLOY_UPDATES,
                    Permission.VIEW_PERFORMANCE, Permission.VIEW_ALERTS, Permission.ACKNOWLEDGE_ALERTS,
                    Permission.VIEW_AUDIT_LOG, Permission.DRIVE, Permission.VIEW_OWN_TRIPS),
            EnumSet.of(Permission.VIEW_ALL_TRIPS, Permission.OPEN_DATABASE_CONSOLE)),
    DRIVER("Driver",
            EnumSet.of(Permission.DRIVE, Permission.VIEW_OWN_TRIPS, Permission.VIEW_ALERTS,
                    Permission.ACKNOWLEDGE_ALERTS),
            EnumSet.of(Permission.RUN_SCENARIOS)),
    TECHNICIAN("Maintenance Technician",
            EnumSet.of(Permission.RUN_DIAGNOSTICS, Permission.APPLY_FIXES, Permission.RUN_SYSTEM_TESTS,
                    Permission.MANAGE_ISSUES, Permission.VIEW_ALERTS, Permission.ACKNOWLEDGE_ALERTS),
            EnumSet.of(Permission.INJECT_FAULTS, Permission.DRIVE));

    private final String label;
    private final Set<Permission> standard;
    private final Set<Permission> full;

    Role(String label, Set<Permission> standard, Set<Permission> extra) {
        this.label = label;
        this.standard = Set.copyOf(standard);
        EnumSet<Permission> all = EnumSet.copyOf(standard);
        all.addAll(extra);
        this.full = Set.copyOf(all);
    }

    public String label() {
        return label;
    }

    /** Permissions for this role at an access level. */
    public Set<Permission> permissions(AccessLevel level) {
        return level == AccessLevel.FULL ? full : standard;
    }
}
