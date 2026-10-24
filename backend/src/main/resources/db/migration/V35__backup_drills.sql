-- Backup-restore drills (P42): a fresh dump of the live database restored into a throwaway Postgres and verified.
CREATE TABLE backup_drills (
    id UUID PRIMARY KEY,
    trigger VARCHAR(10) NOT NULL CHECK (trigger IN ('SCHEDULE','MANUAL')),
    actor VARCHAR(100) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING','PASSED','FAILED','SKIPPED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    schema_version INT,
    expected_rows JSONB,
    restored_rows JSONB,
    dump_bytes BIGINT,
    error VARCHAR(300)
);
CREATE INDEX backup_drills_recent ON backup_drills(started_at DESC);
