package com.selfdriving.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import com.selfdriving.auth.AccessLevel;
import com.selfdriving.auth.PasswordHasher;
import com.selfdriving.auth.Role;
import com.selfdriving.auth.User;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.persistence.Database;
import com.selfdriving.persistence.UserRepository;

/** A fresh in-memory database, a fast password hasher and a clock the test can move. */
class ServiceTestSupport {

    /** A clock that only moves when told to. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T09:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    final Database db = Database.inMemory("s" + UUID.randomUUID().toString().replace("-", ""));
    final UserRepository users = new UserRepository(db);
    final AuditRepository audit = new AuditRepository(db);
    final PasswordHasher hasher = new PasswordHasher(1000);
    final TestClock clock = new TestClock();

    long user(String username, String password, Role role, AccessLevel level) {
        return users.create(username, username + " Test", hasher.hash(password.toCharArray()), role, level);
    }

    Session session(long id) {
        User u = users.find(id).orElseThrow();
        return new Session(u, clock.instant());
    }
}
