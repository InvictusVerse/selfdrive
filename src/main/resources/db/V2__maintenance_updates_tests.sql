-- Stage 4: keeping the car healthy and up to date.

CREATE TABLE issues (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    fault_code       VARCHAR(10)  NOT NULL,
    subsystem        VARCHAR(30)  NOT NULL,
    severity         VARCHAR(10)  NOT NULL,
    description      VARCHAR(300) NOT NULL,
    status           VARCHAR(20)  NOT NULL,   -- OPEN, IN_PROGRESS, FIXED, VERIFIED, CLOSED
    detected_at      TIMESTAMP    NOT NULL,
    assigned_to      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    resolution       VARCHAR(300),
    resolved_at      TIMESTAMP
);
CREATE INDEX issues_status ON issues (status);

CREATE TABLE maintenance_log (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    technician_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    issue_id       BIGINT REFERENCES issues (id) ON DELETE SET NULL,
    action         VARCHAR(200) NOT NULL,
    result         VARCHAR(200) NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE test_runs (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    technician_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    started_at     TIMESTAMP NOT NULL,
    passed         INT       NOT NULL,
    failed         INT       NOT NULL
);

CREATE TABLE test_results (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id      BIGINT       NOT NULL REFERENCES test_runs (id) ON DELETE CASCADE,
    name        VARCHAR(80)  NOT NULL,
    passed      BOOLEAN      NOT NULL,
    duration_ms BIGINT       NOT NULL,
    message     VARCHAR(300)
);

CREATE TABLE software_updates (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    version      VARCHAR(20)  NOT NULL,
    notes        VARCHAR(500) NOT NULL,
    status       VARCHAR(20)  NOT NULL,   -- AVAILABLE, DOWNLOADING, INSTALLING, COMPLETED, FAILED, ROLLED_BACK
    progress     INT          NOT NULL DEFAULT 0,
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    deployed_by  BIGINT REFERENCES users (id) ON DELETE SET NULL
);
