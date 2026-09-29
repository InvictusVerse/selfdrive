package com.selfdriving.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import com.selfdriving.auth.PasswordHasher;
import com.selfdriving.auth.User;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.persistence.UserRepository;

/**
 * Logging in.
 *
 * <ul>
 *   <li>The answer to a wrong username and to a wrong password is the same, and an unknown
 *       username still costs a full hash, so the login screen does not reveal which accounts
 *       exist.</li>
 *   <li>After {@value #MAX_ATTEMPTS} wrong passwords in a row the account is locked for
 *       {@link #LOCK_TIME} and a security alert is raised.</li>
 *   <li>Every login, failure, lock-out and logout goes into the audit log.</li>
 * </ul>
 */
public final class AuthService {

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration LOCK_TIME = Duration.ofMinutes(1);

    /** Result of a login attempt: a session, or a message for the login screen. */
    public record Result(Session session, String message) {

        public boolean ok() {
            return session != null;
        }
    }

    private static final String WRONG = "Wrong username or password";

    private final UserRepository users;
    private final AuditRepository audit;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final String dummyHash;
    private volatile Consumer<String> securityAlerts = message -> { };

    public AuthService(UserRepository users, AuditRepository audit, PasswordHasher hasher, Clock clock) {
        this.users = users;
        this.audit = audit;
        this.hasher = hasher;
        this.clock = clock;
        this.dummyHash = hasher.hash("not-a-real-password-1".toCharArray());
    }

    /** Receives security alerts (repeated failures, lock-outs). */
    public void onSecurityAlert(Consumer<String> listener) {
        this.securityAlerts = listener;
    }

    /** Checks a username and password. The password array is wiped afterwards. */
    public Result login(String username, char[] password) {
        try {
            String name = username == null ? "" : username.trim();
            Optional<User> found = users.findByUsername(name);
            Instant now = clock.instant();
            if (found.isEmpty()) {
                hasher.verify(password, dummyHash); // same cost as a real check
                audit.log(null, name, "LOGIN_FAILED", "Unknown username");
                return new Result(null, WRONG);
            }
            User user = found.get();
            if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
                long seconds = Math.max(1, Duration.between(now, user.lockedUntil()).toSeconds());
                audit.log(user.id(), user.username(), "LOGIN_REFUSED", "Account locked");
                return new Result(null, "Too many wrong passwords. Try again in " + seconds + " s");
            }
            boolean correct = hasher.verify(password, users.passwordHash(user.id()));
            if (!correct) {
                int failures = user.failedAttempts() + 1;
                Instant lockedUntil = null;
                if (failures >= MAX_ATTEMPTS) {
                    lockedUntil = now.plus(LOCK_TIME);
                    failures = 0;
                    audit.log(user.id(), user.username(), "ACCOUNT_LOCKED",
                            MAX_ATTEMPTS + " wrong passwords in a row");
                    securityAlerts.accept("Account '" + user.username() + "' locked after " + MAX_ATTEMPTS
                            + " wrong passwords");
                } else {
                    audit.log(user.id(), user.username(), "LOGIN_FAILED", "Wrong password (" + failures + ")");
                    if (failures >= 3) {
                        securityAlerts.accept("Repeated failed logins for '" + user.username() + "'");
                    }
                }
                users.recordFailure(user.id(), failures, lockedUntil);
                return new Result(null, WRONG);
            }
            if (!user.active()) {
                audit.log(user.id(), user.username(), "LOGIN_REFUSED", "Account deactivated");
                return new Result(null, "This account has been deactivated. Ask an administrator");
            }
            users.recordLogin(user.id(), now);
            audit.log(user.id(), user.username(), "LOGIN", user.role().label());
            User fresh = users.find(user.id()).orElse(user);
            return new Result(new Session(fresh, now), null);
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    public void logout(Session session) {
        audit.log(session.userId(), session.user().username(), "LOGOUT", null);
    }
}
