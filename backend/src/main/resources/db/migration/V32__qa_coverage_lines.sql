-- Line-level coverage per run (P39): which lines of each reported source file were covered or missed, for new-code
-- (changed lines) coverage of a change. module is the report's directory in the repository, path the report's file name.
CREATE TABLE qa_coverage_lines (
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    module VARCHAR(300) NOT NULL,
    path VARCHAR(500) NOT NULL,
    covered INT[] NOT NULL,
    missed INT[] NOT NULL,
    PRIMARY KEY (run_id, module, path)
);

-- Per required suite: minimum line coverage (percent) of the lines a commit changes against the default branch.
ALTER TABLE qa_quality_gates ADD COLUMN min_new_code_coverage JSONB NOT NULL DEFAULT '{}'::jsonb;
