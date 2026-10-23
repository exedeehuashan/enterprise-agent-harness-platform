-- A schedule with an environment runs an API test collection (suite_key is the collection key) against it.
ALTER TABLE qa_schedules ADD COLUMN environment VARCHAR(60);
ALTER TABLE qa_schedules DROP CONSTRAINT qa_schedules_project_key_suite_key_ref_kind_key;
CREATE UNIQUE INDEX qa_schedules_unique ON qa_schedules(project_key, suite_key, ref, kind, COALESCE(environment, ''));
