-- 方案 4 auto-fix (P43): a failed QA run handed to the code agent, and the verification of its pull request.
CREATE TABLE qa_auto_fixes (
    id UUID PRIMARY KEY,
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    suite_key VARCHAR(60) NOT NULL,
    source_run_id UUID NOT NULL UNIQUE,
    source_commit VARCHAR(40) NOT NULL,
    trigger VARCHAR(10) NOT NULL CHECK (trigger IN ('MANUAL','AUTO')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('CODING','NO_CHANGE','CODE_FAILED','VERIFYING','FIXED','PARTLY_FIXED','NOT_FIXED','VERIFY_FAILED')),
    code_task_id UUID NOT NULL,
    code_run_id UUID NOT NULL,
    branch VARCHAR(120),
    pull_request_url VARCHAR(300),
    pr_number INT,
    verify_commit VARCHAR(40),
    verify_run_id UUID,
    result JSONB,
    error VARCHAR(300),
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX qa_auto_fixes_open ON qa_auto_fixes(status) WHERE status IN ('CODING','VERIFYING');
CREATE INDEX qa_auto_fixes_by_project ON qa_auto_fixes(project_key, created_at DESC);

-- Per project: propose an auto-fix for every eligible failed run (each still needs an approver).
CREATE TABLE qa_auto_fix_settings (
    project_key VARCHAR(60) PRIMARY KEY REFERENCES qa_projects(key) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL,
    changed_by VARCHAR(100) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
