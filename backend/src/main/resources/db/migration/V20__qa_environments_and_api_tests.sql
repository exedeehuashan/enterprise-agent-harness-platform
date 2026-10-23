-- Test environments: named base URLs of a deployed system under test. API test runs may only send requests to these.
CREATE TABLE qa_environments (
    project_key VARCHAR(60) NOT NULL REFERENCES qa_projects(key) ON DELETE CASCADE,
    key VARCHAR(60) NOT NULL CHECK (key ~ '^[a-z0-9][a-z0-9-]{1,59}$'),
    name VARCHAR(120) NOT NULL,
    services JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_key, key)
);

INSERT INTO tool_definitions VALUES ('QA_RUN_API_TESTS', 1, 'qa-runner', TRUE, FALSE, FALSE);
