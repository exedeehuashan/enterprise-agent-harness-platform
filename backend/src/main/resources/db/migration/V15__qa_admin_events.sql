-- Audit of QA registry administration (projects, policies, suites, PR triggers, gates): who changed what, before and after.
CREATE TABLE qa_admin_events (
    id BIGSERIAL PRIMARY KEY,
    actor VARCHAR(40) NOT NULL,
    action VARCHAR(40) NOT NULL,
    project_key VARCHAR(60) NOT NULL,
    subject VARCHAR(60),
    before_state JSONB,
    after_state JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX qa_admin_events_by_project ON qa_admin_events(project_key, id);
