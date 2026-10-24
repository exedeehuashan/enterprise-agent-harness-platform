-- Repositories the code agent (Claude Code) may work on, registered by approvers; push only where allowed.
CREATE TABLE code_repositories (
    key VARCHAR(60) PRIMARY KEY CHECK (key ~ '^[a-z0-9][a-z0-9-]{1,59}$'),
    name VARCHAR(120) NOT NULL,
    repository_url VARCHAR(300) NOT NULL,
    default_ref VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    allow_push BOOLEAN NOT NULL DEFAULT FALSE,
    changed_by VARCHAR(100) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Running a coding agent changes code and spends model tokens: it always needs an approver.
INSERT INTO tool_definitions VALUES ('CODE_AGENT_RUN', 1, 'code-agent', TRUE, TRUE, FALSE);
