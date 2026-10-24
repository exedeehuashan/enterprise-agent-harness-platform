-- Flaky-test quarantine suggestions (P40): EAHP proposes, an approver confirms (creating a quarantine) or dismisses.
CREATE TABLE qa_quarantine_suggestions (
    id UUID PRIMARY KEY,
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    suite_key VARCHAR(60) NOT NULL,
    test_class VARCHAR(300) NOT NULL,
    test_name VARCHAR(300) NOT NULL,
    flaky_commits INT NOT NULL,
    evidence JSONB NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING','ACCEPTED','DISMISSED','RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    decided_by VARCHAR(80),
    decided_at TIMESTAMPTZ,
    decision_note VARCHAR(500),
    quarantine_id UUID
);
CREATE UNIQUE INDEX qa_quarantine_suggestions_open ON qa_quarantine_suggestions(project_key, suite_key, test_class, test_name) WHERE status = 'PENDING';
CREATE INDEX qa_quarantine_suggestions_by_project ON qa_quarantine_suggestions(project_key, status, created_at);
