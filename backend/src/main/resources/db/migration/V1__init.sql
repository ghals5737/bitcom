-- docs/implementation-plan.md 3절 / frontend/lib/types.ts 와 1:1

CREATE TABLE employees (
    employee_id          VARCHAR(20)  PRIMARY KEY,               -- EMP-001, ADMIN-001
    name                 VARCHAR(50)  NOT NULL,                  -- 성명 통째
    last_name            VARCHAR(10)  NOT NULL,                  -- 첫 글자
    first_name           VARCHAR(40)  NOT NULL,                  -- 나머지
    birth_date           DATE,                                   -- EMP-007 은 NULL
    phone                VARCHAR(30),
    address              VARCHAR(200),
    department           VARCHAR(50),
    position             VARCHAR(50),
    hire_date            DATE,
    role                 VARCHAR(10)  NOT NULL CHECK (role IN ('ADMIN', 'EMPLOYEE')),
    status               VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'RESIGNED')),
    resigned_at          DATE,
    password_hash        VARCHAR(100) NOT NULL,                  -- bcrypt
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_count   INT          NOT NULL DEFAULT 0,
    locked               BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_employees_status ON employees (status);

CREATE TABLE sessions (
    session_id       VARCHAR(64) PRIMARY KEY,                   -- 랜덤 32바이트 hex
    employee_id      VARCHAR(20) NOT NULL REFERENCES employees (employee_id),
    created_at       TIMESTAMPTZ NOT NULL,
    last_accessed_at TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_sessions_employee ON sessions (employee_id);
CREATE INDEX idx_sessions_expires ON sessions (expires_at);

CREATE TABLE employee_change_logs (
    id          BIGSERIAL    PRIMARY KEY,
    employee_id VARCHAR(20)  NOT NULL REFERENCES employees (employee_id),
    changed_by  VARCHAR(20)  NOT NULL,
    field       VARCHAR(30)  NOT NULL,
    old_value   VARCHAR(200),
    new_value   VARCHAR(200),
    changed_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_change_logs_employee ON employee_change_logs (employee_id, changed_at DESC);

CREATE TABLE background_checks (
    id                  BIGSERIAL    PRIMARY KEY,
    employee_id         VARCHAR(20)  NOT NULL REFERENCES employees (employee_id) ON DELETE CASCADE,
    check_id            VARCHAR(60),                            -- 외부 checkId, POST 실패 시 NULL
    status              VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'CLEAR', 'FLAGGED', 'FAILED', 'TIMEOUT')),
    criminal_record     BOOLEAN,
    education_verified  BOOLEAN,
    employment_verified BOOLEAN,
    credit_score        VARCHAR(10),
    requested_by        VARCHAR(20)  NOT NULL,
    requested_at        TIMESTAMPTZ  NOT NULL,
    completed_at        TIMESTAMPTZ,
    last_polled_at      TIMESTAMPTZ,
    poll_count          INT          NOT NULL DEFAULT 0,
    failure_reason      VARCHAR(300),
    request_payload     JSONB                                    -- 보낸 firstName/lastName/dateOfBirth
);
CREATE INDEX idx_bgc_employee ON background_checks (employee_id, requested_at DESC);
CREATE INDEX idx_bgc_status ON background_checks (status);

CREATE TABLE audit_logs (
    id                 BIGSERIAL   PRIMARY KEY,
    actor_id           VARCHAR(20) NOT NULL,
    action             VARCHAR(30) NOT NULL,
    target_employee_id VARCHAR(20),
    detail             JSONB       NOT NULL DEFAULT '{}'::jsonb, -- 민감값 미기록
    created_at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_target ON audit_logs (target_employee_id, created_at DESC);
