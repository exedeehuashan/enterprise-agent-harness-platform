-- Writing QA results back to GitHub pull requests (commit statuses and one updated comment), opt-in per project.
CREATE TABLE qa_github_write_back (
    project_key VARCHAR(60) PRIMARY KEY REFERENCES qa_projects(key) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL,
    changed_by VARCHAR(100) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Outgoing GitHub calls, retried with backoff. A pending COMMENT or GATE entry per pull request is reused, since both
-- are computed when sent; STATUS entries carry their payload.
CREATE TABLE qa_github_outbox (
    id BIGSERIAL PRIMARY KEY,
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('STATUS','GATE','COMMENT')),
    commit_sha VARCHAR(40) NOT NULL,
    pr_number INTEGER,
    payload JSONB,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_error VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    sent_at TIMESTAMPTZ
);
CREATE INDEX qa_github_outbox_due ON qa_github_outbox(next_attempt_at) WHERE status = 'PENDING';

-- The one EAHP comment per pull request, updated in place.
CREATE TABLE qa_github_comments (
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    pr_number INTEGER NOT NULL,
    comment_id BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_key, pr_number)
);
