-- Version identity is skill_version.version_label only.
-- Remove content digest from skill versions and observation steps.
ALTER TABLE skill_version DROP COLUMN IF EXISTS version_digest;

ALTER TABLE observation_step DROP COLUMN IF EXISTS skill_version_digest;
DROP INDEX IF EXISTS idx_observation_step_skill_version;
CREATE INDEX IF NOT EXISTS idx_observation_step_skill_version_label
    ON observation_step(skill_slug, skill_version_label);
