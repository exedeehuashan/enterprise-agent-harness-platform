-- API test collections drafted by the AI from an OpenAPI document; valid drafts can run against an environment.
CREATE TABLE qa_ai_test_drafts (
    id UUID PRIMARY KEY,
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    commit_sha VARCHAR(40) NOT NULL,
    spec_path VARCHAR(300) NOT NULL,
    service VARCHAR(60) NOT NULL,
    instructions VARCHAR(2000),
    collection JSONB,
    valid BOOLEAN NOT NULL,
    error VARCHAR(500),
    notes VARCHAR(2000),
    provider VARCHAR(20) NOT NULL,
    model VARCHAR(80) NOT NULL,
    input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX qa_ai_test_drafts_by_project ON qa_ai_test_drafts(project_key, created_at DESC);
