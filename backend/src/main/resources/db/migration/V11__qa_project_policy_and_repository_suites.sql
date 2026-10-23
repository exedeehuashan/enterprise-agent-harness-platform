-- Per-project policy. Suites may now also be declared in the project's own repository (versioned with the code);
-- whatever their source, a suite can never exceed this policy.
ALTER TABLE qa_projects
    ADD COLUMN repository_suites_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN suites_file VARCHAR(200) NOT NULL DEFAULT '.eahp/qa-suites.json'
        CHECK (suites_file ~ '^[A-Za-z0-9._-][A-Za-z0-9._/-]{0,199}$' AND suites_file NOT LIKE '%..%'),
    ADD COLUMN allowed_images JSONB NOT NULL DEFAULT '["maven:3.9.11-eclipse-temurin-17"]'
        CHECK (jsonb_typeof(allowed_images) = 'array' AND jsonb_array_length(allowed_images) BETWEEN 1 AND 20),
    ADD COLUMN allow_docker_access BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN allow_network_egress BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN max_timeout_seconds INTEGER NOT NULL DEFAULT 1800 CHECK (max_timeout_seconds BETWEEN 30 AND 3600);

-- online-shop keeps its registry suites as fallback; its integration suite needs Docker (approval-gated) and 40 minutes.
UPDATE qa_projects SET repository_suites_enabled = TRUE, allow_docker_access = TRUE, max_timeout_seconds = 2400
WHERE key = 'online-shop';

-- Pull request triggers may name suites that exist only in the repository file.
ALTER TABLE qa_pr_triggers DROP CONSTRAINT qa_pr_triggers_project_key_suite_key_fkey;
ALTER TABLE qa_pr_triggers ADD CONSTRAINT qa_pr_triggers_project_fkey FOREIGN KEY (project_key) REFERENCES qa_projects(key) ON DELETE CASCADE;
ALTER TABLE qa_pr_triggers ADD CONSTRAINT qa_pr_triggers_suite_key_check CHECK (suite_key ~ '^[a-z0-9][a-z0-9-]{1,59}$');
