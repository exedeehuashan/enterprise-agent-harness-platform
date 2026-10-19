CREATE TABLE agent_runs (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES agent_tasks(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    timeout_seconds INTEGER NOT NULL CHECK (timeout_seconds BETWEEN 1 AND 300),
    tool VARCHAR(40) NOT NULL CHECK (tool = 'DEMO_ANALYZE'),
    snapshot_title VARCHAR(255) NOT NULL,
    snapshot_description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED','TIMED_OUT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    claim_token UUID,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(80),
    UNIQUE(task_id, idempotency_key)
);
CREATE UNIQUE INDEX one_active_run_per_task ON agent_runs(task_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX queued_run_order ON agent_runs(created_at,id) WHERE status = 'QUEUED';
CREATE INDEX running_lease ON agent_runs(lease_until) WHERE status = 'RUNNING';
CREATE TABLE run_steps (
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL CHECK (sequence = 1),
    tool VARCHAR(40) NOT NULL CHECK (tool = 'DEMO_ANALYZE'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED','TIMED_OUT')),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    output TEXT,
    PRIMARY KEY(run_id, sequence)
);
