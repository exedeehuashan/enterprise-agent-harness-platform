ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_status_check;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_status_check CHECK (status IN ('QUEUED','RUNNING','WAITING_APPROVAL','COMPLETED','FAILED','CANCELLED','TIMED_OUT','REJECTED','EXPIRED','NEEDS_REVIEW'));
ALTER TABLE run_steps DROP CONSTRAINT run_steps_status_check;
ALTER TABLE run_steps ADD CONSTRAINT run_steps_status_check CHECK (status IN ('QUEUED','RUNNING','WAITING_APPROVAL','COMPLETED','FAILED','CANCELLED','TIMED_OUT','REJECTED','EXPIRED','NEEDS_REVIEW'));
ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_tool_check;
ALTER TABLE run_steps DROP CONSTRAINT run_steps_tool_check;
CREATE TABLE tool_definitions (
    name VARCHAR(40) PRIMARY KEY,
    version INTEGER NOT NULL CHECK (version > 0),
    target VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL,
    requires_approval BOOLEAN NOT NULL,
    retryable BOOLEAN NOT NULL
);
INSERT INTO tool_definitions VALUES ('DEMO_ANALYZE',1,'local-demo',TRUE,FALSE,TRUE), ('DEMO_REVIEW',1,'local-demo',TRUE,TRUE,FALSE);
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_tool_check CHECK (tool IN ('DEMO_ANALYZE','DEMO_REVIEW'));
ALTER TABLE run_steps ADD CONSTRAINT run_steps_tool_check CHECK (tool IN ('DEMO_ANALYZE','DEMO_REVIEW'));
ALTER TABLE agent_runs ADD COLUMN tool_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE agent_runs ADD COLUMN target VARCHAR(80) NOT NULL DEFAULT 'local-demo';
DROP INDEX one_active_run_per_task;
CREATE UNIQUE INDEX one_active_run_per_task ON agent_runs(task_id) WHERE status IN ('QUEUED','RUNNING','WAITING_APPROVAL');
CREATE TABLE approval_requests (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES agent_runs(id) ON DELETE CASCADE,
    input_digest VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED','INVALIDATED','CANCELLED','CONSUMED')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    decided_by VARCHAR(40),
    reason_code VARCHAR(40),
    execution_token UUID
);
CREATE TABLE tool_invocations (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL,
    claim_token UUID NOT NULL UNIQUE,
    input_digest VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('STARTED','COMPLETED','FAILED','CANCELLED','TIMED_OUT','LOST','UNKNOWN')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    UNIQUE(run_id,attempt)
);
CREATE TABLE audit_events (
    id BIGSERIAL PRIMARY KEY,
    actor VARCHAR(40) NOT NULL,
    action VARCHAR(80) NOT NULL,
    run_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    code VARCHAR(80)
);
CREATE INDEX audit_run_order ON audit_events(run_id,id);
