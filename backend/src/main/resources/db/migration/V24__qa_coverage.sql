-- Code coverage recorded by a QA run (JaCoCo or Cobertura totals), and optional per-suite minimum line coverage in the gate.
CREATE TABLE qa_coverage (
    run_id UUID PRIMARY KEY REFERENCES agent_runs(id) ON DELETE CASCADE,
    project_key VARCHAR(60) NOT NULL,
    suite_key VARCHAR(60) NOT NULL,
    commit_sha VARCHAR(40) NOT NULL,
    lines_covered BIGINT NOT NULL,
    lines_missed BIGINT NOT NULL,
    branches_covered BIGINT NOT NULL,
    branches_missed BIGINT NOT NULL,
    modules JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX qa_coverage_by_suite ON qa_coverage(project_key, suite_key, recorded_at);

ALTER TABLE qa_quality_gates ADD COLUMN min_line_coverage JSONB NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(min_line_coverage) = 'object');
