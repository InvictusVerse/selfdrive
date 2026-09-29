-- Stage 3: people, settings, what happened on the road.

CREATE TABLE users (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    username          VARCHAR(40)  NOT NULL UNIQUE,
    full_name         VARCHAR(100) NOT NULL,
    password_hash     VARCHAR(200) NOT NULL,   -- pbkdf2-sha256$iterations$salt$hash (Base64)
    role              VARCHAR(20)  NOT NULL,   -- ADMIN, DRIVER, TECHNICIAN
    access_level      VARCHAR(20)  NOT NULL,   -- STANDARD, FULL
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts   INT          NOT NULL DEFAULT 0,
    locked_until      TIMESTAMP,
    last_login        TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    username_key      VARCHAR(40)  GENERATED ALWAYS AS (LOWER(username)),   -- 'Asha' and 'asha' are the same user
    CONSTRAINT users_username_ci UNIQUE (username_key),
    CONSTRAINT users_role CHECK (role IN ('ADMIN', 'DRIVER', 'TECHNICIAN')),
    CONSTRAINT users_level CHECK (access_level IN ('STANDARD', 'FULL'))
);

CREATE TABLE settings (
    setting_key   VARCHAR(60)  PRIMARY KEY,
    setting_value VARCHAR(200) NOT NULL,
    updated_by    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE alerts (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    severity         VARCHAR(10)  NOT NULL,
    category         VARCHAR(20)  NOT NULL,
    message          VARCHAR(300) NOT NULL,
    source           VARCHAR(60)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    acknowledged_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    acknowledged_at  TIMESTAMP
);
CREATE INDEX alerts_created ON alerts (created_at);

CREATE TABLE trips (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    driver_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    origin           VARCHAR(120) NOT NULL,
    destination      VARCHAR(120) NOT NULL,
    planned_m        DOUBLE       NOT NULL,
    eta_s            DOUBLE       NOT NULL,
    driven_m         DOUBLE,
    duration_s       DOUBLE,
    energy_kwh       DOUBLE,
    autopilot_share  DOUBLE,
    status           VARCHAR(20)  NOT NULL,   -- IN_PROGRESS, ARRIVED, CANCELLED
    started_at       TIMESTAMP    NOT NULL,
    ended_at         TIMESTAMP
);
CREATE INDEX trips_driver ON trips (driver_id, started_at);

CREATE TABLE audit_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    username    VARCHAR(40),
    action      VARCHAR(40)  NOT NULL,
    details     VARCHAR(300),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX audit_created ON audit_log (created_at);
