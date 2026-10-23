-- Every AI call: which feature, provider and model, tokens used and who asked. The daily budget is enforced from it.
CREATE TABLE ai_usage (
    id BIGSERIAL PRIMARY KEY,
    feature VARCHAR(40) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    model VARCHAR(80) NOT NULL,
    input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    actor VARCHAR(100) NOT NULL,
    error VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ai_usage_by_time ON ai_usage(created_at);

-- AI failure analyses of QA runs (advisory text; never acted on automatically).
CREATE TABLE qa_ai_analyses (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    model VARCHAR(80) NOT NULL,
    analysis JSONB NOT NULL,
    input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX qa_ai_analyses_by_run ON qa_ai_analyses(run_id, created_at DESC);
