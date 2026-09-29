package com.selfdriving.auth;

/** Something a user may do. Checked in the services, not only by hiding buttons. */
public enum Permission {
    DRIVE,
    VIEW_OWN_TRIPS,
    VIEW_ALL_TRIPS,
    RUN_SCENARIOS,
    VIEW_ALERTS,
    ACKNOWLEDGE_ALERTS,
    MANAGE_USERS,
    EDIT_SETTINGS,
    DEPLOY_UPDATES,
    VIEW_PERFORMANCE,
    VIEW_AUDIT_LOG,
    OPEN_DATABASE_CONSOLE,
    RUN_DIAGNOSTICS,
    APPLY_FIXES,
    RUN_SYSTEM_TESTS,
    MANAGE_ISSUES,
    INJECT_FAULTS
}
