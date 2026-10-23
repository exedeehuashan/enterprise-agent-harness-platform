CREATE TABLE model_daily_budgets (
    budget_day DATE PRIMARY KEY,
    reserved_tokens BIGINT NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0),
    request_count INTEGER NOT NULL DEFAULT 0 CHECK (request_count >= 0)
);
CREATE TABLE model_calls (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES agent_tasks(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    provider VARCHAR(40) NOT NULL CHECK (provider = 'mock'),
    model VARCHAR(80) NOT NULL,
    requested_by VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','VALIDATED','FAILED')),
    snapshot_title VARCHAR(255) NOT NULL,
    snapshot_description TEXT,
    snapshot_task_version BIGINT NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    max_output_tokens INTEGER NOT NULL CHECK (max_output_tokens BETWEEN 64 AND 2048),
    timeout_millis INTEGER NOT NULL CHECK (timeout_millis BETWEEN 100 AND 5000),
    reserved_tokens BIGINT NOT NULL CHECK (reserved_tokens > 0),
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    usage_estimated BOOLEAN NOT NULL DEFAULT TRUE,
    cost_usd NUMERIC(12,6) NOT NULL DEFAULT 0 CHECK (cost_usd = 0),
    budget_day DATE NOT NULL REFERENCES model_daily_budgets(budget_day),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ NOT NULL,
    claim_token UUID,
    error_code VARCHAR(80),
    plan JSONB,
    UNIQUE(task_id,idempotency_key),
    CHECK ((status = 'VALIDATED') = (plan IS NOT NULL))
);
CREATE UNIQUE INDEX one_active_plan_per_task ON model_calls(task_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX model_queue_order ON model_calls(created_at,id) WHERE status = 'QUEUED';
