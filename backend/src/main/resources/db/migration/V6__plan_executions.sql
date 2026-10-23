CREATE TABLE plan_executions (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES agent_tasks(id) ON DELETE CASCADE,
    model_call_id UUID NOT NULL UNIQUE REFERENCES model_calls(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    requested_by VARCHAR(40) NOT NULL,
    plan_digest VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED','CANCELLED')),
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX one_active_plan_execution_per_task ON plan_executions(task_id) WHERE status = 'RUNNING';
CREATE INDEX plan_execution_task_order ON plan_executions(task_id,created_at,id);
CREATE TABLE plan_execution_steps (
    execution_id UUID NOT NULL REFERENCES plan_executions(id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL CHECK (sequence BETWEEN 1 AND 5),
    tool VARCHAR(40) NOT NULL,
    tool_version INTEGER NOT NULL,
    target VARCHAR(80) NOT NULL,
    timeout_seconds INTEGER NOT NULL CHECK (timeout_seconds BETWEEN 1 AND 300),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','LAUNCHED','COMPLETED','FAILED','CANCELLED','SKIPPED')),
    run_id UUID UNIQUE REFERENCES agent_runs(id) ON DELETE CASCADE,
    PRIMARY KEY(execution_id, sequence),
    CHECK ((status = 'PENDING' OR status = 'SKIPPED') = (run_id IS NULL))
);
CREATE INDEX launched_plan_steps ON plan_execution_steps(run_id) WHERE status = 'LAUNCHED';
