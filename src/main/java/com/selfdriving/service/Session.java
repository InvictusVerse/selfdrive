package com.selfdriving.service;

import java.time.Instant;

import com.selfdriving.auth.Permission;
import com.selfdriving.auth.User;

/** Who is logged in. Every service call that changes something takes the session and checks it. */
public record Session(User user, Instant loginAt) {

    /** Throws unless the user has the permission. */
    public void require(Permission permission) {
        if (!user.can(permission)) {
            throw new AccessDeniedException(user.role().label() + " (" + user.accessLevel().label()
                    + " access) may not " + permission.name().toLowerCase().replace('_', ' '));
        }
    }

    public boolean can(Permission permission) {
        return user.can(permission);
    }

    public long userId() {
        return user.id();
    }
}
