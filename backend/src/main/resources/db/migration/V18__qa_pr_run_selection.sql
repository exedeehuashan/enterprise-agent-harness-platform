-- How much of a suite each pull request run executed: FULL, MODULES (only the affected Maven modules) or NOT_AFFECTED (skipped).
ALTER TABLE qa_pr_runs
    ADD COLUMN selection VARCHAR(20) CHECK (selection IN ('FULL','MODULES','NOT_AFFECTED')),
    ADD COLUMN selection_reason VARCHAR(40),
    ADD COLUMN selected_modules JSONB;
