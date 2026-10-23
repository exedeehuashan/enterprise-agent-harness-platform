-- Release gate per project: the suites whose latest run at a commit must have passed.
-- (Any failing test already fails its suite, so new or flaky failures need no separate switch; they are reported as notes.)
CREATE TABLE qa_quality_gates (
    project_key VARCHAR(60) PRIMARY KEY REFERENCES qa_projects(key) ON DELETE CASCADE,
    required_suites JSONB NOT NULL CHECK (jsonb_typeof(required_suites) = 'array' AND jsonb_array_length(required_suites) BETWEEN 1 AND 20)
);

INSERT INTO qa_quality_gates(project_key, required_suites) VALUES ('online-shop', '["backend-unit"]');

CREATE INDEX qa_run_ingestions_by_commit ON qa_run_ingestions(project_key, commit_sha, suite_key);
CREATE INDEX qa_run_ingestions_by_time ON qa_run_ingestions(project_key, ingested_at);
