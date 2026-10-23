-- Per-run ingestion of QA evidence: one row once a finished QA run's stored reports have been read.
CREATE TABLE qa_run_ingestions (
    run_id UUID PRIMARY KEY REFERENCES agent_runs(id) ON DELETE CASCADE,
    project_key VARCHAR(60) NOT NULL,
    suite_key VARCHAR(60) NOT NULL,
    commit_sha VARCHAR(40) NOT NULL,
    run_status VARCHAR(20) NOT NULL,
    outcome VARCHAR(40),
    category VARCHAR(40),
    test_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    error VARCHAR(200),
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- One row per executed test case, the basis for history, flakiness and later change-impact analysis.
CREATE TABLE qa_test_results (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    project_key VARCHAR(60) NOT NULL,
    suite_key VARCHAR(60) NOT NULL,
    commit_sha VARCHAR(40) NOT NULL,
    test_class VARCHAR(300) NOT NULL,
    test_name VARCHAR(300) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('PASSED','FAILED','ERROR','SKIPPED')),
    duration_ms INTEGER,
    failure_type VARCHAR(200),
    message VARCHAR(500),
    category VARCHAR(40),
    run_created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX qa_test_results_by_run ON qa_test_results(run_id);
CREATE INDEX qa_test_results_history ON qa_test_results(project_key, suite_key, test_class, test_name, run_created_at);
