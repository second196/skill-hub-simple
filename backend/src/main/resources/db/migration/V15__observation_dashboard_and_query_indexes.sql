-- Observation dashboard support: subagent session parenting, extended step
-- types, query indexes and JSONB generated columns.
--
-- Ported from kmplm V28 (dashboard nodes / parenting + reasoning type),
-- V29 (system type) and V30 (query indexes + generated columns). kmplm has no
-- dedicated observation_dashboard_nodes table; dashboard graph edges derive
-- from observation_session.parent_session_key and step types.

ALTER TABLE observation_session
    ADD COLUMN IF NOT EXISTS parent_session_key VARCHAR(256);

CREATE INDEX IF NOT EXISTS idx_observation_session_parent_key
    ON observation_session(client_row_id, parent_session_key);

-- Backfill parents for existing subagent sessions.
UPDATE observation_session
SET parent_session_key = split_part(session_key, '/subagents/', 1)
WHERE parent_session_key IS NULL
  AND session_key LIKE '%/subagents/%';

-- Step types: keep developer/system records from Codex and Claude as well.
ALTER TABLE observation_step DROP CONSTRAINT IF EXISTS ck_observation_step_type;
ALTER TABLE observation_step
    ADD CONSTRAINT ck_observation_step_type
    CHECK (type IN ('user', 'assistant', 'reasoning', 'skill', 'tool', 'document', 'system'));

-- observation_step(skill_slug, skill_version_label, type)
CREATE INDEX IF NOT EXISTS idx_obs_step_skill_ver_type
    ON observation_step (skill_slug, skill_version_label, type);

-- observation_step(turn_id, type, seq)
CREATE INDEX IF NOT EXISTS idx_obs_step_turn_type_seq
    ON observation_step (turn_id, type, seq);

-- observation_step(turn_id, skill_slug, skill_version_label)
CREATE INDEX IF NOT EXISTS idx_obs_step_turn_skill_ver
    ON observation_step (turn_id, skill_slug, skill_version_label);

-- observation_turn(session_id, started_at, turn_index)
CREATE INDEX IF NOT EXISTS idx_obs_turn_session_started
    ON observation_turn (session_id, started_at, turn_index);

-- Generated columns: avoid re-parsing the payload JSONB in every query.
ALTER TABLE observation_step
    ADD COLUMN IF NOT EXISTS skill_activation boolean
    GENERATED ALWAYS AS (
        (type = 'skill' OR COALESCE(payload->>'match', '') <> '')
    ) STORED;

ALTER TABLE observation_step
    ADD COLUMN IF NOT EXISTS skill_rollup boolean
    GENERATED ALWAYS AS (
        COALESCE(payload->>'rollup', 'false') = 'true'
    ) STORED;

ALTER TABLE observation_step
    ADD COLUMN IF NOT EXISTS usage_scope text
    GENERATED ALWAYS AS (
        payload#>>'{usage,scope}'
    ) STORED;

ALTER TABLE observation_step
    ADD COLUMN IF NOT EXISTS outcome_error boolean
    GENERATED ALWAYS AS (
        COALESCE(payload->>'error', 'false') = 'true'
        OR COALESCE(payload->>'outcome', 'ok') = 'error'
    ) STORED;

CREATE INDEX IF NOT EXISTS idx_obs_step_skill_activation
    ON observation_step (skill_slug, skill_activation, skill_rollup)
    WHERE skill_activation;

CREATE INDEX IF NOT EXISTS idx_obs_step_usage_scope
    ON observation_step (turn_id, usage_scope)
    WHERE usage_scope IS NOT NULL;
