-- Expired artifacts keep their metadata (name, size, SHA-256) as evidence; only the content is removed.
ALTER TABLE run_artifacts ADD COLUMN expired_at TIMESTAMPTZ;

CREATE TABLE retention_runs (
    id BIGSERIAL PRIMARY KEY,
    actor VARCHAR(40) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    artifact_days INTEGER NOT NULL,
    test_result_days INTEGER NOT NULL,
    artifacts_expired INTEGER NOT NULL,
    bytes_freed BIGINT NOT NULL,
    test_results_deleted INTEGER NOT NULL
);
