-- Long-running tools (test suites) may declare deadlines up to one hour; each executor enforces its own maximum.
ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_timeout_seconds_check;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_timeout_seconds_check CHECK (timeout_seconds BETWEEN 1 AND 3600);

-- QA domain (方案 4): projects under test and their predefined suites. Commands are argv arrays, never shell text.
CREATE TABLE qa_projects (
    key VARCHAR(60) PRIMARY KEY CHECK (key ~ '^[a-z0-9][a-z0-9-]{1,59}$'),
    name VARCHAR(120) NOT NULL,
    repository_url VARCHAR(300) NOT NULL CHECK (repository_url ~ '^https://[A-Za-z0-9.-]+/[A-Za-z0-9._/-]+$'),
    default_ref VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE qa_test_suites (
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    key VARCHAR(60) NOT NULL CHECK (key ~ '^[a-z0-9][a-z0-9-]{1,59}$'),
    name VARCHAR(120) NOT NULL,
    description TEXT,
    runner_image VARCHAR(200) NOT NULL,
    command JSONB NOT NULL CHECK (jsonb_typeof(command) = 'array' AND jsonb_array_length(command) BETWEEN 1 AND 40),
    report_dirs JSONB NOT NULL CHECK (jsonb_typeof(report_dirs) = 'array'),
    network VARCHAR(10) NOT NULL CHECK (network IN ('none','egress')),
    docker_access BOOLEAN NOT NULL,
    max_timeout_seconds INTEGER NOT NULL CHECK (max_timeout_seconds BETWEEN 30 AND 3600),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (project_key, key)
);

INSERT INTO qa_projects(key, name, repository_url, default_ref)
VALUES ('online-shop', 'Online Shop', 'https://github.com/exedeehuashan/online-shop.git', 'main');

-- Mirrors the project's own CI jobs: static-unit excludes the three Testcontainers classes; integration needs Docker.
INSERT INTO qa_test_suites(project_key, key, name, description, runner_image, command, report_dirs, network, docker_access, max_timeout_seconds) VALUES
('online-shop', 'backend-unit', 'Backend unit and slice tests',
 'All backend modules except the Testcontainers suites; same selection as the static-unit CI job.',
 'maven:3.9.11-eclipse-temurin-17',
 '["mvn","-B","-ntp","verify","-DskipTests=false","-Dtest=!CatalogApiTest,!PostgresPersistenceTest,!OrderApiTest","-Dsurefire.failIfNoSpecifiedTests=false"]',
 '["target/surefire-reports","target/failsafe-reports"]', 'egress', FALSE, 1800),
('online-shop', 'backend-integration', 'Backend PostgreSQL integration tests',
 'Order persistence/API and Catalog API against disposable PostgreSQL Testcontainers. Requires Docker access.',
 'maven:3.9.11-eclipse-temurin-17',
 '["mvn","-B","-ntp","-pl","backend/order-adapters-persistence,backend/order-api,backend/catalog-api","-am","verify"]',
 '["target/surefire-reports","target/failsafe-reports"]', 'egress', TRUE, 2400);

INSERT INTO tool_definitions VALUES
('QA_RUN_SUITE', 1, 'qa-runner', TRUE, FALSE, FALSE),
('QA_RUN_PRIVILEGED_SUITE', 1, 'qa-runner', TRUE, TRUE, FALSE);
