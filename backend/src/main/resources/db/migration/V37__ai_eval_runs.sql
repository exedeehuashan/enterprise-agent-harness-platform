-- AI evaluation runs (P44): the fixed evaluation set run against the configured model, graded automatically.
CREATE TABLE ai_eval_runs (
    id UUID PRIMARY KEY,
    status VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING','DONE','FAILED')),
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(100) NOT NULL,
    set_version INT NOT NULL,
    total INT NOT NULL DEFAULT 0,
    passed INT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    results JSONB,
    error VARCHAR(300),
    actor VARCHAR(100) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX ai_eval_runs_recent ON ai_eval_runs(started_at DESC);
