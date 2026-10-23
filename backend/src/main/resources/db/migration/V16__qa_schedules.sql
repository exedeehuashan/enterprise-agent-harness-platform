-- Scheduled QA runs: ON_CHANGE runs a suite whenever the ref's head commit changes; CRON runs it on a timetable.
CREATE TABLE qa_schedules (
    id UUID PRIMARY KEY,
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    suite_key VARCHAR(60) NOT NULL CHECK (suite_key ~ '^[a-z0-9][a-z0-9-]{1,59}$'),
    ref VARCHAR(100) NOT NULL,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('ON_CHANGE','CRON')),
    cron VARCHAR(100),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    task_id UUID REFERENCES agent_tasks(id) ON DELETE SET NULL,
    last_commit VARCHAR(40),
    last_run_id UUID REFERENCES agent_runs(id) ON DELETE SET NULL,
    last_checked_at TIMESTAMPTZ,
    last_error VARCHAR(200),
    next_run_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (project_key, suite_key, ref, kind),
    CHECK ((kind = 'CRON') = (cron IS NOT NULL))
);

-- Keep the release gate for online-shop's main branch current: run backend-unit whenever main changes.
INSERT INTO qa_schedules(id, project_key, suite_key, ref, kind)
VALUES ('6b8c0f2e-7f3a-4c1e-9a54-3d2f0e5b7a10', 'online-shop', 'backend-unit', 'main', 'ON_CHANGE');
