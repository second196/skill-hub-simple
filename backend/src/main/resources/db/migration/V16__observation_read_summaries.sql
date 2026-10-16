-- Bounded read models for observation list and skill detail endpoints:
-- four summary tables plus a refresh function.
--
-- Ported from kmplm V33 (tables + initial backfill) and V34 (rebuild logic).
-- kmplm applies V34 as a one-shot repair script; here the rebuild is wrapped in
-- refresh_observation_read_summaries() so services can re-aggregate after
-- ingest or on a schedule (T15 decides the trigger).

CREATE TABLE observation_session_summary (
    session_id BIGINT PRIMARY KEY REFERENCES observation_session(id) ON DELETE CASCADE,
    turn_count INTEGER NOT NULL DEFAULT 0,
    message_count INTEGER NOT NULL DEFAULT 0,
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    token_total BIGINT NOT NULL DEFAULT 0,
    refreshed_at TIMESTAMP(3) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE observation_skill_summary (
    skill_slug VARCHAR(256) NOT NULL,
    version_label VARCHAR(64) NOT NULL DEFAULT '',
    call_count BIGINT NOT NULL DEFAULT 0,
    session_count BIGINT NOT NULL DEFAULT 0,
    client_count BIGINT NOT NULL DEFAULT 0,
    turn_count BIGINT NOT NULL DEFAULT 0,
    token_input BIGINT NOT NULL DEFAULT 0,
    token_cache_read BIGINT NOT NULL DEFAULT 0,
    token_cache_write BIGINT NOT NULL DEFAULT 0,
    token_output BIGINT NOT NULL DEFAULT 0,
    token_requests BIGINT NOT NULL DEFAULT 0,
    token_total BIGINT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMP(3) WITH TIME ZONE,
    refreshed_at TIMESTAMP(3) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (skill_slug, version_label)
);

CREATE TABLE observation_skill_session_summary (
    skill_slug VARCHAR(256) NOT NULL,
    version_label VARCHAR(64) NOT NULL DEFAULT '',
    session_id BIGINT NOT NULL REFERENCES observation_session(id) ON DELETE CASCADE,
    PRIMARY KEY (skill_slug, version_label, session_id)
);

CREATE TABLE observation_skill_client_summary (
    skill_slug VARCHAR(256) NOT NULL,
    version_label VARCHAR(64) NOT NULL DEFAULT '',
    client_id VARCHAR(256) NOT NULL,
    hostname VARCHAR(256),
    os VARCHAR(128),
    session_count BIGINT NOT NULL DEFAULT 0,
    last_seen_at TIMESTAMP(3) WITH TIME ZONE,
    PRIMARY KEY (skill_slug, version_label, client_id)
);

CREATE INDEX idx_observation_skill_session_cursor
    ON observation_skill_session_summary (skill_slug, version_label, session_id DESC);

CREATE INDEX idx_observation_skill_session_lookup
    ON observation_skill_session_summary (session_id, skill_slug, version_label);

CREATE INDEX idx_observation_skill_summary_page
    ON observation_skill_summary (version_label, skill_slug);

CREATE INDEX idx_observation_session_page
    ON observation_session (started_at DESC, id DESC);

CREATE INDEX idx_observation_session_client_page
    ON observation_session (client_row_id, started_at DESC, id DESC);

CREATE INDEX idx_observation_session_summary_refreshed
    ON observation_session_summary (refreshed_at);

-- Rebuild all four summary tables from raw observation data.
-- Mirrors kmplm V33/V34 insert semantics: skill rows exist both per observed
-- version_label and under version_label='*' (all versions aggregated).
CREATE OR REPLACE FUNCTION refresh_observation_read_summaries()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM observation_session_summary;
    INSERT INTO observation_session_summary
        (session_id, turn_count, message_count, tool_call_count, token_total)
    SELECT sess.id,
           COUNT(DISTINCT t.id),
           COUNT(*) FILTER (WHERE st.type IN ('user', 'assistant', 'reasoning')),
           COUNT(*) FILTER (WHERE st.type = 'tool'),
           COALESCE(SUM(COALESCE(NULLIF(st.payload#>>'{usage,total_tokens}', '')::numeric, 0)), 0)::bigint
    FROM observation_session sess
    LEFT JOIN observation_turn t ON t.session_id = sess.id
    LEFT JOIN observation_step st ON st.turn_id = t.id
    GROUP BY sess.id;

    DELETE FROM observation_skill_session_summary;
    INSERT INTO observation_skill_session_summary (skill_slug, version_label, session_id)
    SELECT DISTINCT st.skill_slug, COALESCE(NULLIF(TRIM(st.skill_version_label), ''), ''), t.session_id
    FROM observation_step st
    INNER JOIN observation_turn t ON t.id = st.turn_id
    WHERE st.skill_slug IS NOT NULL AND NOT st.skill_rollup
    UNION
    SELECT DISTINCT st.skill_slug, '*', t.session_id
    FROM observation_step st
    INNER JOIN observation_turn t ON t.id = st.turn_id
    WHERE st.skill_slug IS NOT NULL AND NOT st.skill_rollup;

    DELETE FROM observation_skill_client_summary;
    INSERT INTO observation_skill_client_summary
        (skill_slug, version_label, client_id, hostname, os, session_count, last_seen_at)
    SELECT st.skill_slug, COALESCE(NULLIF(TRIM(st.skill_version_label), ''), ''), c.client_id,
           MAX(c.hostname), MAX(c.os), COUNT(DISTINCT sess.id), MAX(c.last_seen_at)
    FROM observation_step st
    INNER JOIN observation_turn t ON t.id = st.turn_id
    INNER JOIN observation_session sess ON sess.id = t.session_id
    INNER JOIN observation_client c ON c.id = sess.client_row_id
    WHERE st.skill_slug IS NOT NULL AND NOT st.skill_rollup
    GROUP BY st.skill_slug, COALESCE(NULLIF(TRIM(st.skill_version_label), ''), ''), c.client_id
    UNION ALL
    SELECT st.skill_slug, '*', c.client_id, MAX(c.hostname), MAX(c.os),
           COUNT(DISTINCT sess.id), MAX(c.last_seen_at)
    FROM observation_step st
    INNER JOIN observation_turn t ON t.id = st.turn_id
    INNER JOIN observation_session sess ON sess.id = t.session_id
    INNER JOIN observation_client c ON c.id = sess.client_row_id
    WHERE st.skill_slug IS NOT NULL AND NOT st.skill_rollup
    GROUP BY st.skill_slug, c.client_id;

    DELETE FROM observation_skill_summary;
    INSERT INTO observation_skill_summary
        (skill_slug, version_label, call_count, session_count, client_count, turn_count,
         token_input, token_cache_read, token_cache_write, token_output, token_requests,
         token_total, last_used_at)
    SELECT st.skill_slug, COALESCE(NULLIF(TRIM(st.skill_version_label), ''), ''),
           COUNT(*), COUNT(DISTINCT sess.id), COUNT(DISTINCT c.client_id), COUNT(DISTINCT t.id),
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,input_tokens}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,cache_read_input_tokens}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,cache_creation_input_tokens}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,output_tokens}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,request_count}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,total_tokens}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           MAX(st.ts)
    FROM observation_step st
    INNER JOIN observation_turn t ON t.id = st.turn_id
    INNER JOIN observation_session sess ON sess.id = t.session_id
    INNER JOIN observation_client c ON c.id = sess.client_row_id
    WHERE st.skill_slug IS NOT NULL
    GROUP BY st.skill_slug, COALESCE(NULLIF(TRIM(st.skill_version_label), ''), '')
    UNION ALL
    SELECT st.skill_slug, '*', COUNT(*), COUNT(DISTINCT sess.id), COUNT(DISTINCT c.client_id),
           COUNT(DISTINCT t.id),
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,input_tokens}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,cache_read_input_tokens}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,cache_creation_input_tokens}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,output_tokens}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,request_count}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           COALESCE(SUM(CASE WHEN NOT st.skill_rollup THEN COALESCE(NULLIF(st.payload#>>'{usage,total_tokens}', '')::numeric, 0) ELSE 0 END), 0)::bigint,
           MAX(st.ts)
    FROM observation_step st
    INNER JOIN observation_turn t ON t.id = st.turn_id
    INNER JOIN observation_session sess ON sess.id = t.session_id
    INNER JOIN observation_client c ON c.id = sess.client_row_id
    WHERE st.skill_slug IS NOT NULL
    GROUP BY st.skill_slug;
END;
$$;

-- Initial backfill for pre-existing data.
SELECT refresh_observation_read_summaries();
