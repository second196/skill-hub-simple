ALTER TABLE observation_step ADD COLUMN IF NOT EXISTS skill_version_digest CHAR(64);
ALTER TABLE observation_step ADD COLUMN IF NOT EXISTS skill_version_label VARCHAR(64);
ALTER TABLE observation_step ADD COLUMN IF NOT EXISTS skill_version_source VARCHAR(16); -- observed|inferred
CREATE INDEX IF NOT EXISTS idx_observation_step_skill_version ON observation_step(skill_slug, skill_version_digest);
