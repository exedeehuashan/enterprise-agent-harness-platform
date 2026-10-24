-- Performance results per run (P38), read from a suite's k6 summary, for trends and the release gate.
CREATE TABLE qa_performance (
    run_id UUID PRIMARY KEY REFERENCES agent_runs(id) ON DELETE CASCADE,
    project_key VARCHAR(60) NOT NULL,
    suite_key VARCHAR(60) NOT NULL,
    commit_sha VARCHAR(40) NOT NULL,
    outcome VARCHAR(40),
    requests BIGINT NOT NULL,
    requests_per_second DOUBLE PRECISION NOT NULL,
    avg_ms DOUBLE PRECISION NOT NULL,
    p90_ms DOUBLE PRECISION NOT NULL,
    p95_ms DOUBLE PRECISION NOT NULL,
    max_ms DOUBLE PRECISION NOT NULL,
    error_rate DOUBLE PRECISION,
    checks_rate DOUBLE PRECISION,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX qa_performance_by_suite ON qa_performance(project_key, suite_key, recorded_at);

-- Per required suite: {"maxP95Ms": 500, "maxErrorRate": 0.01, "maxP95IncreasePercent": 50}.
ALTER TABLE qa_quality_gates ADD COLUMN performance JSONB NOT NULL DEFAULT '{}'::jsonb;
