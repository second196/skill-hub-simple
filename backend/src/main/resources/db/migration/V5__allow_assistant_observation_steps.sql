ALTER TABLE observation_step DROP CONSTRAINT IF EXISTS ck_observation_step_type;
ALTER TABLE observation_step ADD CONSTRAINT ck_observation_step_type CHECK (type IN ('user', 'assistant', 'skill', 'tool', 'document'));
