-- Which suites run automatically for open pull requests of a registered project.
CREATE TABLE qa_pr_triggers (
    project_key VARCHAR(60) NOT NULL,
    suite_key VARCHAR(60) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (project_key, suite_key),
    FOREIGN KEY (project_key, suite_key) REFERENCES qa_test_suites(project_key, key) ON DELETE CASCADE
);

-- One row per pull request head commit and suite: the deduplication key and the audit of what was (not) run.
CREATE TABLE qa_pr_runs (
    id UUID PRIMARY KEY,
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    pr_number INTEGER NOT NULL CHECK (pr_number > 0),
    head_sha VARCHAR(40) NOT NULL CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    suite_key VARCHAR(60) NOT NULL,
    pr_title VARCHAR(255) NOT NULL,
    pr_url VARCHAR(300) NOT NULL,
    head_ref VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','STARTED','SUPERSEDED','SKIPPED')),
    skip_reason VARCHAR(80),
    task_id UUID REFERENCES agent_tasks(id) ON DELETE SET NULL,
    run_id UUID REFERENCES agent_runs(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    UNIQUE (project_key, pr_number, head_sha, suite_key)
);
CREATE INDEX qa_pr_runs_pending ON qa_pr_runs(created_at) WHERE status = 'PENDING';
CREATE INDEX qa_pr_runs_by_pr ON qa_pr_runs(project_key, pr_number, suite_key, created_at);

CREATE TABLE qa_github_polls (
    project_key VARCHAR(60) PRIMARY KEY REFERENCES qa_projects(key) ON DELETE CASCADE,
    last_polled_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    open_pull_requests INTEGER,
    last_error VARCHAR(200)
);

-- The isolated unit suite runs on every PR update; the Docker-access suite would need an approval each time, so it starts disabled.
INSERT INTO qa_pr_triggers VALUES ('online-shop', 'backend-unit', TRUE), ('online-shop', 'backend-integration', FALSE);
