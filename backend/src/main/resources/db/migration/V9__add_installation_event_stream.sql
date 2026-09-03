ALTER TABLE governance_event_outbox
    ADD COLUMN stream_key VARCHAR(128) NOT NULL DEFAULT 'skillhub:governance:events';

CREATE INDEX idx_event_stream_state
    ON governance_event_outbox (stream_key, state, next_retry_at, created_at);

INSERT INTO runtime_definition (runtime_key, runtime_version, status, capabilities)
VALUES
    ('codex-cli', 'initial', 'ACTIVE', '{"installSkill":true,"installTracker":true,"reportRuntimeData":true,"installerPaths":["bash","powershell"]}'),
    ('codex-vscode', 'initial', 'ACTIVE', '{"installSkill":true,"installTracker":true,"reportRuntimeData":true,"installerPaths":["bash","powershell"]}'),
    ('codex-cursor', 'initial', 'ACTIVE', '{"installSkill":true,"installTracker":true,"reportRuntimeData":true,"installerPaths":["bash","powershell"]}'),
    ('codex-windsurf', 'initial', 'ACTIVE', '{"installSkill":true,"installTracker":true,"reportRuntimeData":true,"installerPaths":["bash","powershell"]}'),
    ('claude-code-otlp', 'initial', 'ACTIVE', '{"installSkill":false,"installTracker":false,"reportRuntimeData":true,"installerPaths":[]}')
ON CONFLICT (runtime_key, runtime_version) DO NOTHING;
