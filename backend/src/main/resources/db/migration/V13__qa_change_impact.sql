-- The pull request's base commit at the time it was seen, needed to compute what the PR changes.
ALTER TABLE qa_pr_runs ADD COLUMN base_sha VARCHAR(40) CHECK (base_sha IS NULL OR base_sha ~ '^[0-9a-f]{40}$');

-- Cached change impact per (project, base, head): deterministic for the pair, so computed once.
CREATE TABLE qa_change_impacts (
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    base_sha VARCHAR(40) NOT NULL CHECK (base_sha ~ '^[0-9a-f]{40}$'),
    head_sha VARCHAR(40) NOT NULL CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    impact JSONB,
    error VARCHAR(200),
    computed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_key, base_sha, head_sha),
    CHECK ((impact IS NULL) <> (error IS NULL))
);
