package com.selfdriving.service;

import java.util.List;
import java.util.regex.Pattern;

import com.selfdriving.auth.AccessLevel;
import com.selfdriving.auth.PasswordHasher;
import com.selfdriving.auth.Permission;
import com.selfdriving.auth.Role;
import com.selfdriving.auth.User;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.persistence.UserRepository;

/**
 * Managing accounts (Admin) and changing one's own password (everyone).
 *
 * <p>Rules: usernames are 3-40 letters, digits, dots, dashes or underscores and unique
 * regardless of case; passwords follow {@link PasswordHasher#problem}; an admin cannot delete,
 * deactivate or demote their own account; the last active admin with full access cannot be
 * removed, so the system can always be managed.
 */
public final class UserService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,40}");

    private final UserRepository users;
    private final AuditRepository audit;
    private final PasswordHasher hasher;

    public UserService(UserRepository users, AuditRepository audit, PasswordHasher hasher) {
        this.users = users;
        this.audit = audit;
        this.hasher = hasher;
    }

    public List<User> list(Session session) {
        session.require(Permission.MANAGE_USERS);
        return users.all();
    }

    public User create(Session session, String username, String fullName, char[] password, Role role,
                       AccessLevel level) {
        session.require(Permission.MANAGE_USERS);
        try {
            String name = username == null ? "" : username.trim();
            if (!USERNAME.matcher(name).matches()) {
                throw new ValidationException("Username: 3 to 40 letters, digits, dots, dashes or underscores");
            }
            String full = cleanName(fullName);
            if (users.findByUsername(name).isPresent()) {
                throw new ValidationException("The username '" + name + "' is already taken");
            }
            String problem = PasswordHasher.problem(name, password);
            if (problem != null) {
                throw new ValidationException("Password: " + problem);
            }
            long id = users.create(name, full, hasher.hash(password), role, level);
            audit.log(session.userId(), session.user().username(), "USER_CREATED",
                    name + " as " + role.label() + " (" + level.label() + ")");
            return users.find(id).orElseThrow();
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    public User update(Session session, long id, String fullName, Role role, AccessLevel level, boolean active) {
        session.require(Permission.MANAGE_USERS);
        User before = users.find(id).orElseThrow(() -> new ValidationException("No such user"));
        String full = cleanName(fullName);
        boolean self = id == session.userId();
        boolean loses = before.role() == Role.ADMIN && before.accessLevel() == AccessLevel.FULL && before.active()
                && (role != Role.ADMIN || level != AccessLevel.FULL || !active);
        if (self && loses) {
            throw new ValidationException("You cannot remove your own administrator access");
        }
        if (loses && users.activeFullAdmins() <= 1) {
            throw new ValidationException("At least one active administrator with full access must remain");
        }
        users.update(id, full, role, level, active);
        audit.log(session.userId(), session.user().username(), "USER_UPDATED", before.username() + ": "
                + role.label() + " (" + level.label() + ")" + (active ? "" : ", deactivated"));
        return users.find(id).orElseThrow();
    }

    public void resetPassword(Session session, long id, char[] password) {
        session.require(Permission.MANAGE_USERS);
        try {
            User user = users.find(id).orElseThrow(() -> new ValidationException("No such user"));
            String problem = PasswordHasher.problem(user.username(), password);
            if (problem != null) {
                throw new ValidationException("Password: " + problem);
            }
            users.setPassword(id, hasher.hash(password));
            audit.log(session.userId(), session.user().username(), "PASSWORD_RESET", user.username());
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    public void delete(Session session, long id) {
        session.require(Permission.MANAGE_USERS);
        User user = users.find(id).orElseThrow(() -> new ValidationException("No such user"));
        if (id == session.userId()) {
            throw new ValidationException("You cannot delete your own account");
        }
        if (user.role() == Role.ADMIN && user.accessLevel() == AccessLevel.FULL && user.active()
                && users.activeFullAdmins() <= 1) {
            throw new ValidationException("At least one active administrator with full access must remain");
        }
        users.delete(id);
        audit.log(session.userId(), session.user().username(), "USER_DELETED", user.username());
    }

    /** Any user: change their own password (the current one is checked first). */
    public void changeOwnPassword(Session session, char[] current, char[] replacement) {
        try {
            if (!hasher.verify(current, users.passwordHash(session.userId()))) {
                throw new ValidationException("The current password is not correct");
            }
            String problem = PasswordHasher.problem(session.user().username(), replacement);
            if (problem != null) {
                throw new ValidationException("New password: " + problem);
            }
            users.setPassword(session.userId(), hasher.hash(replacement));
            audit.log(session.userId(), session.user().username(), "PASSWORD_CHANGED", null);
        } finally {
            PasswordHasher.wipe(current);
            PasswordHasher.wipe(replacement);
        }
    }

    private static String cleanName(String fullName) {
        String full = fullName == null ? "" : fullName.trim();
        if (full.isEmpty() || full.length() > 100) {
            throw new ValidationException("Full name: 1 to 100 characters");
        }
        return full;
    }
}
