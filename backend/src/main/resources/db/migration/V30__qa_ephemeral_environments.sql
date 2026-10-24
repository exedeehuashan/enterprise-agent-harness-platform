-- Per-commit test environments: a project's compose stack started for one commit, registered as a temporary
-- environment for API tests and environment suites, and removed when it expires.
ALTER TABLE qa_environments ADD COLUMN network VARCHAR(120);
ALTER TABLE qa_environments ADD COLUMN ephemeral BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE qa_ephemeral_environments (
    id UUID PRIMARY KEY,
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    template VARCHAR(60) NOT NULL,
    commit_sha VARCHAR(40) NOT NULL,
    environment_key VARCHAR(60) NOT NULL,
    compose_project VARCHAR(80) NOT NULL UNIQUE,
    network VARCHAR(120),
    status VARCHAR(10) NOT NULL CHECK (status IN ('STARTING','RUNNING','STOPPED','FAILED')),
    run_id UUID,
    error VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    stopped_at TIMESTAMPTZ
);
CREATE INDEX qa_ephemeral_environments_live ON qa_ephemeral_environments(expires_at) WHERE status IN ('STARTING','RUNNING');

-- Starting an environment builds and runs a commit's code with host Docker access: it needs approval, like Docker suites.
INSERT INTO tool_definitions VALUES
('QA_START_ENVIRONMENT', 1, 'qa-runner', TRUE, TRUE, FALSE),
('QA_STOP_ENVIRONMENT', 1, 'qa-runner', TRUE, FALSE, FALSE),
('QA_RUN_ENVIRONMENT_TESTS', 1, 'qa-runner', TRUE, TRUE, FALSE);
