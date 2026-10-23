-- Executable tools are now owned by code (ToolRegistry); the database keeps a name format check only.
ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_tool_check;
ALTER TABLE run_steps DROP CONSTRAINT run_steps_tool_check;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_tool_check CHECK (tool ~ '^[A-Z][A-Z0-9_]{1,39}$');
ALTER TABLE run_steps ADD CONSTRAINT run_steps_tool_check CHECK (tool ~ '^[A-Z][A-Z0-9_]{1,39}$');

-- Canonical JSON validated by the tool's executor; NULL for tools without arguments.
ALTER TABLE agent_runs ADD COLUMN arguments TEXT CHECK (arguments IS NULL OR length(arguments) <= 16384);

CREATE TABLE run_artifacts (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    invocation_id UUID NOT NULL REFERENCES tool_invocations(id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    name VARCHAR(100) NOT NULL CHECK (name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'),
    media_type VARCHAR(60) NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 0 AND 5242880),
    sha256 VARCHAR(64) NOT NULL,
    content BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(run_id, attempt, name)
);
