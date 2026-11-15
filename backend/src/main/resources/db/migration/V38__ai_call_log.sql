-- AI call log: what each AI call was for, how long it took, and (redacted, bounded, cleared after the retention window)
-- the prompt EAHP sent and the answer it got back, so administrators can see exactly how the platform used the model.
ALTER TABLE ai_usage
    ADD COLUMN source_type VARCHAR(20),
    ADD COLUMN source_id VARCHAR(120),
    ADD COLUMN max_output_tokens INTEGER,
    ADD COLUMN duration_ms BIGINT,
    ADD COLUMN system_prompt TEXT,
    ADD COLUMN user_prompt TEXT,
    ADD COLUMN answer_text TEXT,
    ADD COLUMN content_expired_at TIMESTAMPTZ;
CREATE INDEX ai_usage_by_source ON ai_usage(source_type, source_id);
CREATE INDEX ai_usage_by_feature ON ai_usage(feature, id DESC);
