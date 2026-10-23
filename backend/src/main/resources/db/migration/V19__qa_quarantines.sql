-- Quarantined tests: an approver accepts that a known-bad test may fail without blocking the release gate, for a bounded time.
CREATE TABLE qa_quarantines (
    id UUID PRIMARY KEY,
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    suite_key VARCHAR(60) NOT NULL,
    test_class VARCHAR(300) NOT NULL,
    test_name VARCHAR(300) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    released_by VARCHAR(100),
    CHECK (expires_at > created_at)
);
-- At most one open quarantine per test; expired ones are closed before a new one is created.
CREATE UNIQUE INDEX qa_quarantines_open ON qa_quarantines(project_key, suite_key, test_class, test_name) WHERE released_at IS NULL;
