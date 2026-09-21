package com.km.skillhub.observation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class ObservationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ObservationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, String> platformSkills() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT slug, name FROM skill");
        Map<String, String> skills = new LinkedHashMap<String, String>();
        for (Map<String, Object> row : rows) {
            skills.put(String.valueOf(row.get("slug")), String.valueOf(row.get("name")));
        }
        return skills;
    }

    public long upsertClient(String clientId, String hostname, String os, String meta) {
        Long id = jdbc.queryForObject(
                "INSERT INTO observation_client (client_id, hostname, os, meta) VALUES (?,?,?,?) " +
                        "ON CONFLICT (client_id) DO UPDATE SET " +
                        "hostname=COALESCE(EXCLUDED.hostname, observation_client.hostname), " +
                        "os=COALESCE(EXCLUDED.os, observation_client.os), " +
                        "meta=COALESCE(EXCLUDED.meta, observation_client.meta), " +
                        "last_seen_at=CURRENT_TIMESTAMP RETURNING id",
                Long.class, clientId, hostname, os, meta);
        if (id == null) throw new IllegalStateException("无法写入观测客户端");
        return id;
    }

    public long upsertSession(long clientRowId, String sessionKey, String clientName, Instant startedAt, Instant endedAt) {
        return upsertSession(clientRowId, sessionKey, clientName, startedAt, endedAt, null);
    }

    public long upsertSession(long clientRowId, String sessionKey, String clientName, Instant startedAt, Instant endedAt, String title) {
        Long id = jdbc.queryForObject(
                "INSERT INTO observation_session (client_row_id, session_key, client_name, started_at, ended_at, title) " +
                        "VALUES (?,?,?,?,?,?) ON CONFLICT (client_row_id, session_key) DO UPDATE SET " +
                        "client_name=EXCLUDED.client_name, " +
                        "started_at=COALESCE(EXCLUDED.started_at, observation_session.started_at), " +
                        "ended_at=COALESCE(EXCLUDED.ended_at, observation_session.ended_at), " +
                        "title=COALESCE(EXCLUDED.title, observation_session.title), " +
                        "updated_at=CURRENT_TIMESTAMP RETURNING id",
                Long.class, clientRowId, sessionKey, clientName, timestamp(startedAt), timestamp(endedAt), title);
        if (id == null) throw new IllegalStateException("无法写入观测会话");
        return id;
    }

    public long upsertTurn(long sessionId, int turnIndex, Instant startedAt, String userText) {
        Long id = jdbc.queryForObject(
                "INSERT INTO observation_turn (session_id, turn_index, started_at, user_text) VALUES (?,?,?,?) " +
                        "ON CONFLICT (session_id, turn_index) DO UPDATE SET " +
                        "started_at=COALESCE(EXCLUDED.started_at, observation_turn.started_at), " +
                        "user_text=CASE WHEN length(EXCLUDED.user_text) >= length(observation_turn.user_text) " +
                        "THEN EXCLUDED.user_text ELSE observation_turn.user_text END, " +
                        "updated_at=CURRENT_TIMESTAMP RETURNING id",
                Long.class, sessionId, turnIndex, timestamp(startedAt), userText == null ? "" : userText);
        if (id == null) throw new IllegalStateException("无法写入观测回合");
        return id;
    }

    public void upsertStep(long turnId, String stepId, int seq, String type, Instant ts, String skillSlug, String payloadJson) {
        upsertStep(turnId, stepId, seq, type, ts, skillSlug, payloadJson, null, null);
    }

    public void upsertStep(long turnId, String stepId, int seq, String type, Instant ts, String skillSlug, String payloadJson,
                           String skillVersionLabel, String skillVersionSource) {
        jdbc.update(
                "INSERT INTO observation_step (turn_id, step_id, seq, type, ts, skill_slug, payload, " +
                        "skill_version_label, skill_version_source) " +
                        "VALUES (?,?,?,?,?,?,?::jsonb,?,?) " +
                        "ON CONFLICT (turn_id, step_id) DO UPDATE SET " +
                        "seq=EXCLUDED.seq, type=EXCLUDED.type, ts=COALESCE(EXCLUDED.ts, observation_step.ts), " +
                        "skill_slug=COALESCE(EXCLUDED.skill_slug, observation_step.skill_slug), " +
                        "payload=CASE WHEN length(EXCLUDED.payload::text) >= length(observation_step.payload::text) " +
                        "THEN EXCLUDED.payload ELSE observation_step.payload END, " +
                        "skill_version_label=COALESCE(EXCLUDED.skill_version_label, observation_step.skill_version_label), " +
                        "skill_version_source=COALESCE(EXCLUDED.skill_version_source, observation_step.skill_version_source), " +
                        "updated_at=CURRENT_TIMESTAMP",
                turnId, stepId, seq, type, timestamp(ts), skillSlug, ObservationPayloads.forJsonb(payloadJson),
                skillVersionLabel, skillVersionSource);
    }

    public void saveBatch(String batchId, String clientId, int sessions, int turns, int steps, int skipped) {
        jdbc.update(
                "INSERT INTO observation_ingest_batch (batch_id, client_id, session_count, turn_count, step_count, skipped_step_count) " +
                        "VALUES (?,?,?,?,?,?) ON CONFLICT (batch_id) DO UPDATE SET " +
                        "session_count=EXCLUDED.session_count, turn_count=EXCLUDED.turn_count, " +
                        "step_count=EXCLUDED.step_count, skipped_step_count=EXCLUDED.skipped_step_count, " +
                        "received_at=CURRENT_TIMESTAMP",
                batchId, clientId, sessions, turns, steps, skipped);
    }

    private static String usageSum(String field, String filter) {
        // payload.usage.<field> numeric sum; filter e.g. non-rollup skill steps
        String base = "COALESCE(NULLIF(st.payload#>>'{usage," + field + "}','')::numeric, 0)";
        if (filter == null || filter.isEmpty()) {
            return "COALESCE(SUM(" + base + "), 0)";
        }
        return "COALESCE(SUM(CASE WHEN " + filter + " THEN " + base + " ELSE 0 END), 0)";
    }

    private static final String NON_ROLLUP_SKILL = "st.type='skill' AND COALESCE(st.payload->>'rollup','false') <> 'true'";

    /**
     * Optional version filter for observation_step queries.
     * Values: omitted/null/"all" (no filter), or a SemVer version label (e.g. 1.0.0 / v1.0.0).
     * Observation skill identity is skill_version_label only — content digest is not used.
     *
     * SQL is inlined as escaped literals (no JDBC placeholders) so the same fragment can be
     * safely embedded into multiple aggregates (usageSum CASE WHEN ...) without parameter-count bugs.
     */
    static final class VersionFilter {
        static final VersionFilter ALL = new VersionFilter("", new Object[0]);
        final String sql;
        final Object[] args;

        private VersionFilter(String sql, Object[] args) {
            this.sql = sql == null ? "" : sql;
            this.args = args == null ? new Object[0] : args;
        }

        static VersionFilter of(String versionFilter) {
            if (versionFilter == null) return ALL;
            String value = versionFilter.trim();
            if (value.isEmpty() || "all".equalsIgnoreCase(value)) return ALL;
            if ("unknown".equalsIgnoreCase(value)) {
                return new VersionFilter(" AND (st.skill_version_label IS NULL OR TRIM(st.skill_version_label)='')", new Object[0]);
            }
            String label = value.trim();
            if (label.length() > 1 && (label.charAt(0) == 'v' || label.charAt(0) == 'V') && Character.isDigit(label.charAt(1))) {
                label = label.substring(1);
            }
            String safeLabel = sqlLiteral(label);
            String safePrefixed = sqlLiteral("v" + label);
            return new VersionFilter(
                    " AND (TRIM(st.skill_version_label)='" + safeLabel + "' OR TRIM(st.skill_version_label)='" + safePrefixed + "')",
                    new Object[0]);
        }

        private static String sqlLiteral(String value) {
            if (value == null) return "";
            return value.replace("'", "''");
        }

        Object[] prepend(Object first) {
            Object[] out = new Object[1 + args.length];
            out[0] = first;
            System.arraycopy(args, 0, out, 1, args.length);
            return out;
        }

        Object[] prepend(Object first, Object second) {
            Object[] out = new Object[2 + args.length];
            out[0] = first;
            out[1] = second;
            System.arraycopy(args, 0, out, 2, args.length);
            return out;
        }
    }

    /** COUNT/queryForObject that never throws on empty result — observation pages must stay up. */
    private Integer safeCount(String sql, Object... args) {
        try {
            Integer value = jdbc.queryForObject(sql, Integer.class, args);
            return value == null ? Integer.valueOf(0) : value;
        } catch (RuntimeException ex) {
            return Integer.valueOf(0);
        }
    }

    private static Map<String, Object> emptyTokenUsage() {
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        usage.put("inputTokens", Long.valueOf(0L));
        usage.put("cacheReadTokens", Long.valueOf(0L));
        usage.put("cacheWriteTokens", Long.valueOf(0L));
        usage.put("outputTokens", Long.valueOf(0L));
        usage.put("totalTokens", Long.valueOf(0L));
        usage.put("requestCount", Long.valueOf(0L));
        usage.put("turnsWithTokens", Integer.valueOf(0));
        usage.put("input_tokens", Long.valueOf(0L));
        usage.put("cache_read_input_tokens", Long.valueOf(0L));
        usage.put("cache_creation_input_tokens", Long.valueOf(0L));
        usage.put("output_tokens", Long.valueOf(0L));
        usage.put("total_tokens", Long.valueOf(0L));
        usage.put("request_count", Long.valueOf(0L));
        return usage;
    }

    /**
     * Skill-level token usage aggregated from observation_step.payload.usage
     * written by observer scan. Leaf skill steps only (rollup excluded).
     */
    public Map<String, Object> skillTokenSummary(String slug) {
        return skillTokenSummary(slug, null);
    }

    public Map<String, Object> skillTokenSummary(String slug, String versionFilter) {
        try {
            VersionFilter vf = VersionFilter.of(versionFilter);
            // Version filter lives only in WHERE (literal SQL, no bind params).
            // Aggregates keep NON_ROLLUP_SKILL only — avoids repeating ? placeholders in usageSum.
            Map<String, Object> row = firstOrNull(jdbc.queryForList(
                    "SELECT " +
                            usageSum("input_tokens", NON_ROLLUP_SKILL) + " AS token_input, " +
                            usageSum("cache_read_input_tokens", NON_ROLLUP_SKILL) + " AS token_cache_read, " +
                            usageSum("cache_creation_input_tokens", NON_ROLLUP_SKILL) + " AS token_cache_write, " +
                            usageSum("output_tokens", NON_ROLLUP_SKILL) + " AS token_output, " +
                            usageSum("total_tokens", NON_ROLLUP_SKILL) + " AS token_total, " +
                            usageSum("request_count", NON_ROLLUP_SKILL) + " AS token_requests, " +
                            "COUNT(*) FILTER (WHERE " + NON_ROLLUP_SKILL + " AND jsonb_exists(st.payload, 'usage')) AS turns_with_tokens " +
                            "FROM observation_step st " +
                            "WHERE st.skill_slug=? AND st.type='skill'" + vf.sql,
                    vf.prepend(slug)));
            long input = longOf(row == null ? null : row.get("token_input"));
            long cacheRead = longOf(row == null ? null : row.get("token_cache_read"));
            long cacheWrite = longOf(row == null ? null : row.get("token_cache_write"));
            long output = longOf(row == null ? null : row.get("token_output"));
            long total = longOf(row == null ? null : row.get("token_total"));
            if (total <= 0 && (input > 0 || cacheRead > 0 || cacheWrite > 0 || output > 0)) {
                total = input + cacheRead + cacheWrite + output;
            }
            long requests = longOf(row == null ? null : row.get("token_requests"));
            int turnsWithTokens = intOf(row == null ? null : row.get("turns_with_tokens"));
            Map<String, Object> usage = new LinkedHashMap<String, Object>();
            usage.put("inputTokens", Long.valueOf(input));
            usage.put("cacheReadTokens", Long.valueOf(cacheRead));
            usage.put("cacheWriteTokens", Long.valueOf(cacheWrite));
            usage.put("outputTokens", Long.valueOf(output));
            usage.put("totalTokens", Long.valueOf(total));
            usage.put("requestCount", Long.valueOf(requests));
            usage.put("turnsWithTokens", Integer.valueOf(turnsWithTokens));
            usage.put("input_tokens", Long.valueOf(input));
            usage.put("cache_read_input_tokens", Long.valueOf(cacheRead));
            usage.put("cache_creation_input_tokens", Long.valueOf(cacheWrite));
            usage.put("output_tokens", Long.valueOf(output));
            usage.put("total_tokens", Long.valueOf(total));
            usage.put("request_count", Long.valueOf(requests));
            return usage;
        } catch (RuntimeException ex) {
            return emptyTokenUsage();
        }
    }

    public List<Map<String, Object>> listObservedSkills() {
        // Platform observation cards only: skill must exist in the platform skill table.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.slug, s.name, s.category, s.description, " +
                        "COUNT(*) FILTER (WHERE st.type='skill') AS skill_calls, " +
                        "COUNT(*) FILTER (WHERE st.type IN ('tool','document') AND (st.payload->>'match') IS NOT NULL) AS file_loads, " +
                        "COUNT(DISTINCT sess.id) AS session_count, " +
                        "COUNT(DISTINCT c.client_id) AS client_count, " +
                        "MAX(st.ts) AS last_used_at, " +
                        usageSum("total_tokens", NON_ROLLUP_SKILL) + " AS token_total " +
                        "FROM skill s " +
                        "JOIN observation_step st ON st.skill_slug=s.slug " +
                        "JOIN observation_turn t ON t.id=st.turn_id " +
                        "JOIN observation_session sess ON sess.id=t.session_id " +
                        "JOIN observation_client c ON c.id=sess.client_row_id " +
                        "GROUP BY s.slug, s.name, s.category, s.description " +
                        "ORDER BY last_used_at DESC NULLS LAST, skill_calls DESC");
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, List<Map<String, Object>>> trends = trendBySkill(null);
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>(row);
            Object skillCalls = row.get("skill_calls");
            Object fileLoads = row.get("file_loads");
            int callCount = (skillCalls instanceof Number ? ((Number) skillCalls).intValue() : 0)
                    + (fileLoads instanceof Number ? ((Number) fileLoads).intValue() : 0);
            item.put("call_count", Integer.valueOf(callCount));
            item.put("token_total", Long.valueOf(longOf(row.get("token_total"))));
            String slug = String.valueOf(row.get("slug"));
            Map<String, Object> quality = skillQualitySummary(slug);
            quality.put("tokenUsage", skillTokenSummary(slug));
            item.put("quality", quality);
            item.put("health_score", quality.get("healthScore"));
            item.put("error_rate", quality.get("errorRate"));
            item.put("reload_rate", quality.get("reloadRate"));
            item.put("load_complete_rate", quality.get("loadCompleteRate"));
            item.put("progress_label", quality.get("progressLabel"));
            item.put("trend", fillTrend(trends.get(slug)));
            result.add(item);
        }
        return result;
    }

    /**
     * Behavioral quality metrics for one platform skill (no global aggregate score).
     * Reload is turn-level: within one turn, >=2 non-rollup skill loads for the same slug.
     * Cross-turn SOP re-triggers are normal and are NOT counted as reload.
     * health = 0.35*loadComplete + 0.30*(1-error) + 0.25*(1-reload) + 0.10*progress
     */
    /**
     * Lightweight quality for list/cards: no path distribution, no evidence funnel, no tools_after scan.
     */
    public Map<String, Object> skillQualitySummary(String slug) {
        return skillQualitySummary(slug, null);
    }

    public Map<String, Object> skillQualitySummary(String slug, String versionFilter) {
        try {
            VersionFilter vf = VersionFilter.of(versionFilter);
            Map<String, Object> row = firstOrNull(jdbc.queryForList(
                    "WITH skill_steps AS (" +
                            " SELECT st.id, st.turn_id, st.seq, st.payload, t.session_id" +
                            " FROM observation_step st JOIN observation_turn t ON t.id=st.turn_id" +
                            " WHERE st.skill_slug=? AND st.type='skill'" +
                            "   AND COALESCE(st.payload->>'rollup','false') <> 'true'" + vf.sql +
                            "), turn_loads AS (" +
                            " SELECT turn_id, COUNT(*) AS loads" +
                            " FROM skill_steps GROUP BY turn_id" +
                            " )" +
                            "SELECT" +
                            " (SELECT COUNT(*) FROM skill_steps) AS calls," +
                            " (SELECT COUNT(DISTINCT session_id) FROM skill_steps) AS sessions," +
                            " (SELECT COUNT(*) FROM turn_loads) AS skill_turns," +
                            " (SELECT COUNT(*) FROM skill_steps WHERE COALESCE(payload->>'outcome','ok')='error') AS errors," +
                            " (SELECT COUNT(*) FROM skill_steps WHERE COALESCE(payload->>'match','') IN ('file','path') " +
                            "   OR COALESCE(payload->>'path','') ILIKE '%SKILL.md%') AS complete_loads," +
                            " (SELECT COUNT(*) FROM turn_loads WHERE loads >= 2) AS reload_turns",
                    vf.prepend(slug)));
            int calls = intOf(row == null ? null : row.get("calls"));
            int sessions = intOf(row == null ? null : row.get("sessions"));
            int skillTurns = intOf(row == null ? null : row.get("skill_turns"));
            int errors = intOf(row == null ? null : row.get("errors"));
            int complete = intOf(row == null ? null : row.get("complete_loads"));
            int reloadTurns = intOf(row == null ? null : row.get("reload_turns"));
            double errorRate = calls == 0 ? 0d : (double) errors / calls;
            double reloadRate = skillTurns == 0 ? 0d : (double) reloadTurns / skillTurns;
            double loadCompleteRate = calls == 0 ? 0d : (double) complete / calls;
            double progress = clamp01(calls == 0 ? 0d : (double) complete / Math.max(calls, 1) * 0.5d + 0.5d * (1d - errorRate));
            double health = 100d * (0.35d * loadCompleteRate + 0.30d * (1d - errorRate) + 0.25d * (1d - reloadRate) + 0.10d * progress);

            Map<String, Object> quality = new LinkedHashMap<String, Object>();
            quality.put("calls", Integer.valueOf(calls));
            quality.put("sessions", Integer.valueOf(sessions));
            quality.put("skillTurns", Integer.valueOf(skillTurns));
            quality.put("errors", Integer.valueOf(errors));
            quality.put("completeLoads", Integer.valueOf(complete));
            quality.put("reloadTurns", Integer.valueOf(reloadTurns));
            quality.put("errorRate", Double.valueOf(round2(errorRate)));
            quality.put("reloadRate", Double.valueOf(round2(reloadRate)));
            quality.put("loadCompleteRate", Double.valueOf(round2(loadCompleteRate)));
            quality.put("progress", Double.valueOf(round2(progress)));
            quality.put("progressLabel", progressLabel(progress));
            quality.put("healthScore", Integer.valueOf((int) Math.round(health)));
            quality.put("healthLabel", healthLabel(health));
            return quality;
        } catch (RuntimeException ex) {
            Map<String, Object> quality = new LinkedHashMap<String, Object>();
            quality.put("calls", Integer.valueOf(0));
            quality.put("sessions", Integer.valueOf(0));
            quality.put("skillTurns", Integer.valueOf(0));
            quality.put("errors", Integer.valueOf(0));
            quality.put("completeLoads", Integer.valueOf(0));
            quality.put("reloadTurns", Integer.valueOf(0));
            quality.put("errorRate", Double.valueOf(0d));
            quality.put("reloadRate", Double.valueOf(0d));
            quality.put("loadCompleteRate", Double.valueOf(0d));
            quality.put("progress", Double.valueOf(0d));
            quality.put("progressLabel", progressLabel(0d));
            quality.put("healthScore", Integer.valueOf(0));
            quality.put("healthLabel", healthLabel(0d));
            return quality;
        }
    }

    public Map<String, Object> skillQuality(String slug) {
        return skillQuality(slug, null);
    }

    public Map<String, Object> skillQuality(String slug, String versionFilter) {
        VersionFilter vf = VersionFilter.of(versionFilter);
        Map<String, Object> row = firstOrNull(jdbc.queryForList(
                "WITH skill_steps AS (" +
                        " SELECT st.id, st.turn_id, st.seq, st.payload, t.session_id" +
                        " FROM observation_step st JOIN observation_turn t ON t.id=st.turn_id" +
                        " WHERE st.skill_slug=? AND st.type='skill'" +
                        "   AND COALESCE(st.payload->>'rollup','false') <> 'true'" + vf.sql +
                        "), turn_loads AS (" +
                        " SELECT turn_id, COUNT(*) AS loads" +
                        " FROM skill_steps GROUP BY turn_id" +
                        " )" +
                        "SELECT" +
                        " (SELECT COUNT(*) FROM skill_steps) AS calls," +
                        " (SELECT COUNT(DISTINCT session_id) FROM skill_steps) AS sessions," +
                        " (SELECT COUNT(*) FROM turn_loads) AS skill_turns," +
                        " (SELECT COUNT(*) FROM skill_steps WHERE COALESCE(payload->>'outcome','ok')='error') AS errors," +
                        " (SELECT COUNT(*) FROM skill_steps WHERE COALESCE(payload->>'match','') IN ('file','path') " +
                        "   OR COALESCE(payload->>'path','') ILIKE '%SKILL.md%') AS complete_loads," +
                        " (SELECT COUNT(*) FROM turn_loads WHERE loads >= 2) AS reload_turns," +
                        " (SELECT COALESCE(AVG(tool_cnt),0) FROM (" +
                        "   SELECT (SELECT COUNT(*) FROM observation_step tool WHERE tool.turn_id=ss.turn_id AND tool.type='tool' AND tool.seq > ss.seq) AS tool_cnt" +
                        "   FROM skill_steps ss" +
                        " ) tools_after)",
                vf.prepend(slug)));
        int calls = intOf(row == null ? null : row.get("calls"));
        int sessions = intOf(row == null ? null : row.get("sessions"));
        int skillTurns = intOf(row == null ? null : row.get("skill_turns"));
        int errors = intOf(row == null ? null : row.get("errors"));
        int complete = intOf(row == null ? null : row.get("complete_loads"));
        int reloadTurns = intOf(row == null ? null : row.get("reload_turns"));
        double avgToolsAfter = row == null ? 0d : doubleOf(row.get("tools_after"));

        double errorRate = calls == 0 ? 0d : (double) errors / calls;
        double reloadRate = skillTurns == 0 ? 0d : (double) reloadTurns / skillTurns;
        double loadCompleteRate = calls == 0 ? 0d : (double) complete / calls;
        double progress = clamp01(avgToolsAfter / 3.0d);
        double health = 100d * (0.35d * loadCompleteRate + 0.30d * (1d - errorRate) + 0.25d * (1d - reloadRate) + 0.10d * progress);

        Map<String, Object> quality = new LinkedHashMap<String, Object>();
        quality.put("calls", Integer.valueOf(calls));
        quality.put("sessions", Integer.valueOf(sessions));
        quality.put("skillTurns", Integer.valueOf(skillTurns));
        quality.put("errors", Integer.valueOf(errors));
        quality.put("completeLoads", Integer.valueOf(complete));
        quality.put("reloadTurns", Integer.valueOf(reloadTurns));
        quality.put("reloadSessions", Integer.valueOf(reloadTurns));
        quality.put("errorRate", Double.valueOf(round2(errorRate)));
        quality.put("reloadRate", Double.valueOf(round2(reloadRate)));
        quality.put("loadCompleteRate", Double.valueOf(round2(loadCompleteRate)));
        quality.put("progress", Double.valueOf(round2(progress)));
        quality.put("progressLabel", progressLabel(progress));
        quality.put("healthScore", Integer.valueOf((int) Math.round(health)));
        quality.put("healthLabel", healthLabel(health));
        quality.put("evidence", skillEvidenceLevels(slug, versionFilter));
        quality.put("reloadNote", "重读按 Turn 统计：同一轮对话内重复载入该技能才算；跨 Turn 的 SOP 正常触发不计入");
        quality.put("formula", "健康分 = 载入完整 × 35% +（1 − 错误率）× 30% +（1 − 重读率）× 25% + 推进 × 10%");
        return quality;
    }

    /**
     * Turn-level evidence funnel L1→L4 for one platform skill.
     * Denominator = turns that already attributed this skill (L1 turns).
     * L1 trigger | L2 load ok | L3 follow-up work | L4 closed-loop (L3 + no error + no abnormal same-turn reload)
     */
    public Map<String, Object> skillEvidenceLevels(String slug) {
        return skillEvidenceLevels(slug, null);
    }

    public Map<String, Object> skillEvidenceLevels(String slug, String versionFilter) {
        VersionFilter vf = VersionFilter.of(versionFilter);
        Map<String, Object> row = firstOrNull(jdbc.queryForList(
                "WITH skill_steps AS (" +
                        " SELECT st.id, st.turn_id, st.seq, st.payload, t.session_id" +
                        " FROM observation_step st JOIN observation_turn t ON t.id=st.turn_id" +
                        " WHERE st.skill_slug=? AND st.type='skill'" +
                        "   AND COALESCE(st.payload->>'rollup','false') <> 'true'" + vf.sql +
                        "), turn_stats AS (" +
                        " SELECT turn_id, session_id," +
                        "   COUNT(*) AS loads," +
                        "   COUNT(*) FILTER (WHERE COALESCE(payload->>'outcome','ok') = 'error') AS error_loads," +
                        "   COUNT(*) FILTER (WHERE COALESCE(payload->>'outcome','ok') <> 'error') AS ok_loads," +
                        "   COUNT(*) FILTER (WHERE (COALESCE(payload->>'match','') IN ('file','path') OR COALESCE(payload->>'path','') ILIKE '%SKILL.md%') " +
                        "     AND COALESCE(payload->>'outcome','ok') <> 'error') AS complete_ok_loads," +
                        "   MIN(CASE WHEN COALESCE(payload->>'outcome','ok') <> 'error' THEN seq END) AS first_ok_seq" +
                        " FROM skill_steps GROUP BY turn_id, session_id" +
                        "), turn_flags AS (" +
                        " SELECT ts.*," +
                        "   CASE WHEN ts.ok_loads > 0 AND (ts.complete_ok_loads > 0 OR ts.ok_loads > 0) THEN 1 ELSE 0 END AS l2," +
                        "   CASE WHEN ts.first_ok_seq IS NOT NULL AND EXISTS (" +
                        "     SELECT 1 FROM observation_step x" +
                        "     WHERE x.turn_id = ts.turn_id" +
                        "       AND x.type IN ('tool','document')" +
                        "       AND x.seq > ts.first_ok_seq" +
                        "   ) THEN 1 ELSE 0 END AS has_followup" +
                        " FROM turn_stats ts" +
                        " )" +
                        "SELECT" +
                        " COUNT(*) AS l1_turns," +
                        " COALESCE(SUM(l2), 0) AS l2_turns," +
                        " COALESCE(SUM(CASE WHEN l2 = 1 AND has_followup = 1 THEN 1 ELSE 0 END), 0) AS l3_turns," +
                        " COALESCE(SUM(CASE WHEN l2 = 1 AND has_followup = 1 AND error_loads = 0 AND ok_loads < 2 THEN 1 ELSE 0 END), 0) AS l4_turns" +
                        " FROM turn_flags",
                vf.prepend(slug)));
        int l1 = intOf(row == null ? null : row.get("l1_turns"));
        int l2 = intOf(row == null ? null : row.get("l2_turns"));
        int l3 = intOf(row == null ? null : row.get("l3_turns"));
        int l4 = intOf(row == null ? null : row.get("l4_turns"));
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("denominator", Integer.valueOf(l1));
        evidence.put("levels", evidenceLevelList(l1, l2, l3, l4));
        return evidence;
    }

    private List<Map<String, Object>> evidenceLevelList(int l1, int l2, int l3, int l4) {
        List<Map<String, Object>> levels = new ArrayList<Map<String, Object>>();
        levels.add(evidenceLevel("L1", "触发成功", "出现 skill 步且归因正确", l1, l1));
        levels.add(evidenceLevel("L2", "加载成功", "完整 SKILL.md / 引用，且非 error", l2, l1));
        levels.add(evidenceLevel("L3", "执行推进", "载入后有工具 / 文档产出", l3, l1));
        levels.add(evidenceLevel("L4", "行为闭环", "无硬失败、无同 Turn 异常重读", l4, l1));
        return levels;
    }

    private Map<String, Object> evidenceLevel(String code, String title, String hint, int count, int denominator) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("code", code);
        item.put("title", title);
        item.put("hint", hint);
        item.put("count", Integer.valueOf(count));
        item.put("rate", Double.valueOf(denominator <= 0 ? 0d : round2((double) count / denominator)));
        return item;
    }

    public List<Map<String, Object>> skillProblemSessions(String slug, int limit) {
        return skillProblemSessions(slug, limit, null);
    }

    public List<Map<String, Object>> skillProblemSessions(String slug, int limit, String versionFilter) {
        VersionFilter vf = VersionFilter.of(versionFilter);
        return jdbc.queryForList(
                "WITH skill_steps AS (" +
                        " SELECT st.id, st.turn_id, st.seq, st.payload, t.session_id" +
                        " FROM observation_step st JOIN observation_turn t ON t.id=st.turn_id" +
                        " WHERE st.skill_slug=? AND st.type='skill'" +
                        "   AND COALESCE(st.payload->>'rollup','false') <> 'true'" + vf.sql +
                        "), turn_stats AS (" +
                        " SELECT turn_id, session_id," +
                        "   COUNT(*) AS loads," +
                        "   COUNT(*) FILTER (WHERE COALESCE(payload->>'outcome','ok')='error') AS errors," +
                        "   COUNT(*) FILTER (WHERE COALESCE(payload->>'match','') IN ('file','path') OR COALESCE(payload->>'path','') ILIKE '%SKILL.md%') AS complete_loads" +
                        " FROM skill_steps GROUP BY turn_id, session_id" +
                        "), flags AS (" +
                        " SELECT session_id," +
                        "   SUM(loads) AS loads," +
                        "   COUNT(*) FILTER (WHERE loads >= 2) AS reload_turns," +
                        "   SUM(errors) AS errors," +
                        "   SUM(complete_loads) AS complete_loads" +
                        " FROM turn_stats GROUP BY session_id" +
                        " )" +
                        "SELECT sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, sess.started_at," +
                        " f.loads, f.errors, f.complete_loads, f.reload_turns," +
                        " CASE WHEN f.errors > 0 THEN 0 ELSE 1 END AS error_rank," +
                        " CASE WHEN f.reload_turns > 0 THEN 0 ELSE 1 END AS reload_rank," +
                        " CASE WHEN f.complete_loads = 0 THEN 0 ELSE 1 END AS complete_rank" +
                        " FROM flags f" +
                        " JOIN observation_session sess ON sess.id=f.session_id" +
                        " JOIN observation_client c ON c.id=sess.client_row_id" +
                        " ORDER BY error_rank ASC, reload_rank ASC, complete_rank ASC, sess.started_at DESC NULLS LAST" +
                        " LIMIT ?",
                vf.prepend(slug, Integer.valueOf(limit)));
    }

    private static String progressLabel(double progress) {
        if (progress >= 0.67d) return "高";
        if (progress >= 0.33d) return "中";
        return "低";
    }

    private static String healthLabel(double health) {
        if (health >= 75d) return "健康";
        if (health >= 60d) return "一般";
        return "偏弱";
    }

    private static double clamp01(double value) {
        if (value < 0d) return 0d;
        if (value > 1d) return 1d;
        return value;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static int intOf(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static long longOf(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            try {
                return (long) Double.parseDouble(String.valueOf(value).trim());
            } catch (Exception ignoredAgain) {
                return 0L;
            }
        }
    }

    private static double doubleOf(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null) return 0d;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return 0d;
        }
    }

    private static Map<String, Object> firstOrNull(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("skillCount", jdbc.queryForObject(
                "SELECT COUNT(DISTINCT st.skill_slug) FROM observation_step st JOIN skill s ON s.slug=st.skill_slug", Integer.class));
        result.put("callCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM observation_step st JOIN skill s ON s.slug=st.skill_slug " +
                        "WHERE (st.type='skill' OR (st.type IN ('tool','document') AND (st.payload->>'match') IS NOT NULL))", Integer.class));
        result.put("sessionCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM observation_session", Integer.class));
        result.put("clientCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM observation_client", Integer.class));
        result.put("turnCount", jdbc.queryForObject("SELECT COUNT(*) FROM observation_turn", Integer.class));
        result.put("trend", fillTrend(trendBySkill(null).get("__all__")));
        return result;
    }

    /**
     * First meaningful user text as session display title.
     * Skips system-injected context (AGENTS.md / environment_context / ide_opened_file etc.).
     * Must be used with alias {@code sess} and aggregate via MIN/MAX in GROUP BY queries.
     */
    private static final String SESSION_TITLE_JOIN =
            " LEFT JOIN LATERAL (" +
                    " SELECT LEFT(COALESCE(TRIM(t2.user_text), ''), 80) AS title" +
                    " FROM observation_turn t2" +
                    " WHERE t2.session_id = sess.id" +
                    "   AND COALESCE(TRIM(t2.user_text), '') <> ''" +
                    "   AND NOT (" +
                    "     LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '# agents.md%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE 'agents.md%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<environment_context%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<ide_opened_file%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<system-reminder%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<system_reminder%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<instructions%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<skills_instructions%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<collaboration_mode%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<permissions instructions%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<base_instructions%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<runtime context%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE 'caveat:%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '[system%'" +
                    "     OR LOWER(COALESCE(TRIM(t2.user_text), '')) LIKE '<cwd>%'" +
                    "   )" +
                    " ORDER BY t2.turn_index ASC" +
                    " LIMIT 1" +
                    " ) stitle ON TRUE " +
                    " LEFT JOIN LATERAL (" +
                    " SELECT LEFT(COALESCE(TRIM(t3.user_text), ''), 240) AS raw_title" +
                    " FROM observation_turn t3" +
                    " WHERE t3.session_id = sess.id" +
                    "   AND COALESCE(TRIM(t3.user_text), '') <> ''" +
                    " ORDER BY t3.turn_index ASC" +
                    " LIMIT 1" +
                    " ) sraw ON TRUE ";

    static boolean looksLikeSystemSessionText(String text) {
        if (text == null) return true;
        String value = text.replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) return true;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("# agents.md")
                || lower.startsWith("agents.md")
                || lower.startsWith("<environment_context")
                || lower.startsWith("<ide_opened_file")
                || lower.startsWith("<system-reminder")
                || lower.startsWith("<system_reminder")
                || lower.startsWith("<instructions")
                || lower.startsWith("<skills_instructions")
                || lower.startsWith("<collaboration_mode")
                || lower.startsWith("<permissions instructions")
                || lower.startsWith("<base_instructions")
                || lower.startsWith("<runtime context")
                || lower.startsWith("caveat:")
                || lower.startsWith("[system")
                || lower.startsWith("<cwd>")
                || lower.startsWith("you are mimo")
                || lower.startsWith("you are claude")
                || lower.startsWith("you are codex")) {
            return true;
        }
        return lower.contains("<cwd>") && lower.contains("</cwd>") && lower.contains("environment");
    }

    static String cleanSessionTitleText(Object raw) {
        if (raw == null) return "";
        String text = String.valueOf(raw).replaceAll("\\s+", " ").trim();
        if (text.isEmpty() || looksLikeSystemSessionText(text)) return "";
        text = text.replaceFirst("^<[^>]+>\\s*", "").trim();
        if (text.isEmpty() || looksLikeSystemSessionText(text)) return "";
        return text;
    }

    static String lastPathSegment(String path) {
        if (path == null) return "";
        String normalized = path.trim().replace('\\', '/');
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        int idx = normalized.lastIndexOf('/');
        String seg = idx >= 0 ? normalized.substring(idx + 1) : normalized;
        seg = seg.trim().replaceAll("[^A-Za-z0-9._\\-]+$", "").trim();
        if (seg.isEmpty() || seg.length() > 48) return "";
        return seg;
    }

    static String projectHintFromText(Object raw) {
        if (raw == null) return "";
        String text = String.valueOf(raw);
        java.util.regex.Matcher cwd = java.util.regex.Pattern
                .compile("(?i)<cwd>\\s*([^<\\s]+)\\s*</cwd>")
                .matcher(text);
        if (cwd.find()) return lastPathSegment(cwd.group(1));
        java.util.regex.Matcher agents = java.util.regex.Pattern
                .compile("(?i)AGENTS\\.md\\s+instructions\\s+for\\s+([^\\s<]+)")
                .matcher(text);
        if (agents.find()) return lastPathSegment(agents.group(1));
        return projectHintFromSessionKey(text);
    }

    static String projectHintFromSessionKey(Object sessionKey) {
        if (sessionKey == null) return "";
        String key = String.valueOf(sessionKey).trim();
        if (key.isEmpty()) return "";
        int colon = key.lastIndexOf(':');
        if (colon >= 0 && colon < key.length() - 1) key = key.substring(colon + 1);
        int sub = key.toLowerCase(Locale.ROOT).indexOf("/subagents/");
        if (sub > 0) key = key.substring(0, sub);
        int slash = key.indexOf('/');
        if (slash >= 0) key = key.substring(0, slash);
        key = key.trim();
        if (key.isEmpty() || key.matches("(?i)[0-9a-f-]{16,}")) return "";
        int marker = key.lastIndexOf("--");
        if (marker >= 0) {
            String tail = key.substring(marker + 2).trim();
            if (!tail.isEmpty() && tail.length() <= 48 && !tail.matches("(?i)[0-9a-f-]{16,}")) return tail;
        }
        if (key.length() <= 48 && !key.matches("(?i)[0-9a-f-]{16,}")) return key;
        return "";
    }

    private static String normalizeSessionTitle(Object title) {
        String text = cleanSessionTitleText(title);
        if (text.isEmpty()) return "";
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    private static String sessionTitleFallback(Map<String, Object> session) {
        String project = projectHintFromText(session.get("raw_title"));
        if (project.isEmpty()) project = projectHintFromSessionKey(session.get("session_key"));
        if (!project.isEmpty()) {
            return project.length() > 60 ? project.substring(0, 60) + "…" : project;
        }
        String client = session.get("client_name") != null ? String.valueOf(session.get("client_name"))
                : (session.get("hostname") != null ? String.valueOf(session.get("hostname")) : "会话");
        if ("claude-code".equalsIgnoreCase(client)) client = "Claude Code";
        if ("codex".equalsIgnoreCase(client)) client = "Codex";
        Object started = session.get("started_at");
        String day = started == null ? "" : String.valueOf(started);
        if (day.length() >= 10) day = day.substring(0, 10);
        return day.isEmpty() ? client : (client + " · " + day);
    }

    static String sessionTitleFallbackForTest(Map<String, Object> session) {
        return sessionTitleFallback(session);
    }

    private void enrichSessionTitles(List<Map<String, Object>> sessions) {
        if (sessions == null) return;
        for (Map<String, Object> session : sessions) {
            String title = normalizeSessionTitle(session.get("title"));
            if (title.isEmpty()) title = normalizeSessionTitle(session.get("session_title"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> turns = (List<Map<String, Object>>) session.get("turns");
            if (title.isEmpty() && turns != null) {
                for (Map<String, Object> turn : turns) {
                    String cleaned = normalizeSessionTitle(turn.get("user_text"));
                    if (!cleaned.isEmpty()) {
                        title = cleaned;
                        break;
                    }
                }
            }
            if (title.isEmpty()) {
                String project = projectHintFromText(session.get("raw_title"));
                if (!project.isEmpty()) title = project;
            }
            if (title.isEmpty()) title = sessionTitleFallback(session);
            session.put("title", title);
            session.put("session_title", title);
            session.remove("raw_title");
        }
    }

    /**
     * Collapse only exact clone rows (same client + started_at + turn_count + title).
     * Sessions that share a display title but differ by session_key/cwd stay separate.
     */
    static List<Map<String, Object>> mergeSessionRowsForDisplay(List<Map<String, Object>> sessions) {
        if (sessions == null || sessions.size() < 2) {
            return sessions == null ? new ArrayList<Map<String, Object>>() : sessions;
        }
        List<Map<String, Object>> kept = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> exactIndex = new HashMap<String, Map<String, Object>>();
        for (Map<String, Object> session : sessions) {
            String exactKey = String.valueOf(session.get("client_id") == null ? "" : session.get("client_id"))
                    + '|' + String.valueOf(session.get("client_name") == null ? "" : session.get("client_name"))
                    + '|' + String.valueOf(session.get("started_at") == null ? "" : session.get("started_at"))
                    + '|' + String.valueOf(session.get("turn_count") == null ? "" : session.get("turn_count"))
                    + '|' + String.valueOf(session.get("title") == null ? "" : session.get("title"));
            Map<String, Object> existing = exactIndex.get(exactKey);
            if (existing == null) {
                Map<String, Object> copy = new LinkedHashMap<String, Object>(session);
                exactIndex.put(exactKey, copy);
                kept.add(copy);
            } else {
                absorbSessionMetrics(existing, session);
            }
        }
        return kept;
    }

    private static void absorbSessionMetrics(Map<String, Object> primary, Map<String, Object> other) {
        if (primary == null || other == null || primary == other) return;
        primary.put("turn_count", Long.valueOf(Math.max(longOf(primary.get("turn_count")), longOf(other.get("turn_count")))));
        for (String field : new String[] { "token_total", "token_input", "token_cache_read", "token_cache_write", "token_output", "token_requests" }) {
            long sum = longOf(primary.get(field)) + longOf(other.get(field));
            primary.put(field, Long.valueOf(sum));
        }
        Object ended = other.get("ended_at");
        if (ended != null) {
            Object current = primary.get("ended_at");
            if (current == null || String.valueOf(ended).compareTo(String.valueOf(current)) > 0) {
                primary.put("ended_at", ended);
            }
        }
        Object started = other.get("started_at");
        if (started != null) {
            Object current = primary.get("started_at");
            if (current == null || String.valueOf(started).compareTo(String.valueOf(current)) < 0) {
                primary.put("started_at", started);
            }
        }
        @SuppressWarnings("unchecked")
        List<Object> aliases = (List<Object>) primary.get("merged_session_ids");
        List<Object> mergedIds = aliases == null ? new ArrayList<Object>() : new ArrayList<Object>(aliases);
        Object otherId = other.get("id");
        if (otherId != null && !mergedIds.contains(otherId) && !otherId.equals(primary.get("id"))) {
            mergedIds.add(otherId);
        }
        if (!mergedIds.isEmpty()) primary.put("merged_session_ids", mergedIds);
        primary.put("merged_count", Integer.valueOf(mergedIds.size() + 1));
    }

    public Map<String, Object> sessionList(String clientId, Long sessionId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("clients", jdbc.queryForList(
                "SELECT c.client_id, c.hostname, c.os, COUNT(DISTINCT sess.id) AS session_count, MAX(c.last_seen_at) AS last_seen_at " +
                        "FROM observation_client c " +
                        "LEFT JOIN observation_session sess ON sess.client_row_id=c.id " +
                        "GROUP BY c.client_id, c.hostname, c.os ORDER BY last_seen_at DESC"));
        String sessionSql = "SELECT sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, " +
                "sess.started_at, sess.ended_at, COUNT(DISTINCT t.id) AS turn_count, " +
                "COALESCE(NULLIF(MAX(sess.title), ''), MIN(stitle.title)) AS title, MIN(sraw.raw_title) AS raw_title " +
                "FROM observation_session sess " +
                "JOIN observation_client c ON c.id=sess.client_row_id " +
                "LEFT JOIN observation_turn t ON t.session_id=sess.id " +
                SESSION_TITLE_JOIN;
        List<Object> args = new ArrayList<Object>();
        if (clientId != null && !clientId.trim().isEmpty()) {
            sessionSql += " WHERE c.client_id=?";
            args.add(clientId.trim());
        }
        sessionSql += " GROUP BY sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, sess.started_at, sess.ended_at " +
                "ORDER BY sess.started_at DESC NULLS LAST, sess.id DESC";
        List<Map<String, Object>> sessions = args.isEmpty()
                ? jdbc.queryForList(sessionSql)
                : jdbc.queryForList(sessionSql, args.toArray());
        enrichSessionTitles(sessions);
        sessions = mergeSessionRowsForDisplay(sessions);
        result.put("sessions", sessions);
        Long selectedId = sessionId;
        if (selectedId != null) {
            boolean found = false;
            for (int i = 0; i < sessions.size(); i++) {
                Object id = sessions.get(i).get("id");
                long value = id instanceof Number ? ((Number) id).longValue() : Long.parseLong(String.valueOf(id));
                if (value == selectedId.longValue()) {
                    found = true;
                    break;
                }
            }
            if (!found) selectedId = null;
        }
        if (selectedId == null && !sessions.isEmpty()) {
            Object id = sessions.get(0).get("id");
            selectedId = id instanceof Number ? ((Number) id).longValue() : Long.valueOf(String.valueOf(id));
        }
        result.put("selectedSessionId", selectedId);
        result.put("selectedSession", selectedId == null ? null : sessionChain(selectedId, null));
        return result;
    }

    public Map<String, Object> skillDetail(String slug, String clientId, Long sessionId) {
        return skillDetail(slug, clientId, sessionId, null);
    }

    public Map<String, Object> skillDetail(String slug, String clientId, Long sessionId, String versionFilter) {
        VersionFilter vf = VersionFilter.of(versionFilter);
        Map<String, Object> skill = resolveSkillCard(slug);
        if (skill == null) throw new IllegalArgumentException("Skill不存在");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("skill", skill);
        result.put("versionFilter", versionFilter == null || versionFilter.trim().isEmpty() ? "all" : versionFilter.trim());
        List<Map<String, Object>> versions = java.util.Collections.emptyList();
        try {
            versions = skillVersions(slug);
        } catch (RuntimeException ignored) {
            versions = java.util.Collections.emptyList();
        }
        result.put("versions", versions);

        Map<String, Object> kpis = new LinkedHashMap<String, Object>();
        kpis.put("callCount", safeCount(
                "SELECT COUNT(*) FROM observation_step st " +
                        "WHERE st.skill_slug=? AND (st.type='skill' " +
                        "OR (st.type IN ('tool','document') AND (st.payload->>'match') IS NOT NULL))" + vf.sql,
                vf.prepend(slug)));
        kpis.put("sessionCount", safeCount(
                "SELECT COUNT(DISTINCT sess.id) FROM observation_session sess " +
                        "JOIN observation_turn t ON t.session_id=sess.id " +
                        "JOIN observation_step st ON st.turn_id=t.id WHERE st.skill_slug=?" + vf.sql,
                vf.prepend(slug)));
        kpis.put("clientCount", safeCount(
                "SELECT COUNT(DISTINCT c.client_id) FROM observation_client c " +
                        "JOIN observation_session sess ON sess.client_row_id=c.id " +
                        "JOIN observation_turn t ON t.session_id=sess.id " +
                        "JOIN observation_step st ON st.turn_id=t.id WHERE st.skill_slug=?" + vf.sql,
                vf.prepend(slug)));
        kpis.put("turnCount", safeCount(
                "SELECT COUNT(DISTINCT t.id) FROM observation_turn t " +
                        "JOIN observation_step st ON st.turn_id=t.id WHERE st.skill_slug=?" + vf.sql,
                vf.prepend(slug)));
        result.put("kpis", kpis);
        Map<String, Object> tokenUsage = skillTokenSummary(slug, versionFilter);
        kpis.put("tokenTotal", tokenUsage.get("totalTokens"));
        kpis.put("tokenInput", tokenUsage.get("inputTokens"));
        kpis.put("tokenCacheRead", tokenUsage.get("cacheReadTokens"));
        kpis.put("tokenCacheWrite", tokenUsage.get("cacheWriteTokens"));
        kpis.put("tokenOutput", tokenUsage.get("outputTokens"));
        kpis.put("tokenRequests", tokenUsage.get("requestCount"));
        kpis.put("tokenUsage", tokenUsage);
        result.put("tokenUsage", tokenUsage);
        Map<String, List<Map<String, Object>>> trends;
        try {
            trends = trendBySkill(slug);
        } catch (RuntimeException ignored) {
            trends = new LinkedHashMap<String, List<Map<String, Object>>>();
        }
        result.put("trend", fillTrend(trends.get(slug)));
        Map<String, Object> quality;
        try {
            quality = skillQuality(slug, versionFilter);
        } catch (RuntimeException ignored) {
            quality = skillQualitySummary(slug, versionFilter);
        }
        quality.put("tokenUsage", tokenUsage);
        result.put("quality", quality);
        List<Map<String, Object>> problemSessions;
        try {
            problemSessions = skillProblemSessions(slug, 20, versionFilter);
        } catch (RuntimeException ignored) {
            problemSessions = java.util.Collections.emptyList();
        }
        result.put("problemSessions", problemSessions);

        List<Map<String, Object>> clients;
        try {
            clients = jdbc.queryForList(
                    "SELECT c.client_id, c.hostname, c.os, COUNT(DISTINCT sess.id) AS session_count, MAX(c.last_seen_at) AS last_seen_at " +
                            "FROM observation_client c " +
                            "JOIN observation_session sess ON sess.client_row_id=c.id " +
                            "JOIN observation_turn t ON t.session_id=sess.id " +
                            "JOIN observation_step st ON st.turn_id=t.id " +
                            "WHERE st.skill_slug=?" + vf.sql + " GROUP BY c.client_id, c.hostname, c.os ORDER BY last_seen_at DESC",
                    vf.prepend(slug));
        } catch (RuntimeException ignored) {
            clients = java.util.Collections.emptyList();
        }
        result.put("clients", clients);

        String sessionSql = "SELECT sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, " +
                "sess.started_at, sess.ended_at, COUNT(DISTINCT t.id) AS turn_count, " +
                "COALESCE(NULLIF(MAX(sess.title), ''), MIN(stitle.title)) AS title, MIN(sraw.raw_title) AS raw_title, " +
                usageSum("input_tokens", NON_ROLLUP_SKILL) + " AS token_input, " +
                usageSum("cache_read_input_tokens", NON_ROLLUP_SKILL) + " AS token_cache_read, " +
                usageSum("cache_creation_input_tokens", NON_ROLLUP_SKILL) + " AS token_cache_write, " +
                usageSum("output_tokens", NON_ROLLUP_SKILL) + " AS token_output, " +
                usageSum("total_tokens", NON_ROLLUP_SKILL) + " AS token_total, " +
                usageSum("request_count", NON_ROLLUP_SKILL) + " AS token_requests " +
                "FROM observation_session sess " +
                "JOIN observation_client c ON c.id=sess.client_row_id " +
                "JOIN observation_turn t ON t.session_id=sess.id " +
                "JOIN observation_step st ON st.turn_id=t.id " +
                SESSION_TITLE_JOIN +
                "WHERE st.skill_slug=?" + vf.sql;
        List<Object> args = new ArrayList<Object>();
        args.add(slug);
        for (Object arg : vf.args) args.add(arg);
        if (clientId != null && !clientId.trim().isEmpty()) {
            sessionSql += " AND c.client_id=?";
            args.add(clientId.trim());
        }
        sessionSql += " GROUP BY sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, sess.started_at, sess.ended_at " +
                "ORDER BY sess.started_at DESC NULLS LAST, sess.id DESC LIMIT 100";
        List<Map<String, Object>> sessions;
        try {
            sessions = jdbc.queryForList(sessionSql, args.toArray());
        } catch (RuntimeException ignored) {
            sessions = new ArrayList<Map<String, Object>>();
        }
        for (Map<String, Object> session : sessions) {
            long total = longOf(session.get("token_total"));
            long input = longOf(session.get("token_input"));
            long cacheRead = longOf(session.get("token_cache_read"));
            long cacheWrite = longOf(session.get("token_cache_write"));
            long output = longOf(session.get("token_output"));
            if (total <= 0 && (input > 0 || cacheRead > 0 || cacheWrite > 0 || output > 0)) {
                total = input + cacheRead + cacheWrite + output;
                session.put("token_total", Long.valueOf(total));
            }
        }
        enrichSessionTitles(sessions);
        sessions = mergeSessionRowsForDisplay(sessions);
        result.put("sessions", sessions);

        Long selectedId = sessionId;
        if (selectedId != null) {
            boolean found = false;
            for (Map<String, Object> session : sessions) {
                Object id = session.get("id");
                long value = id instanceof Number ? ((Number) id).longValue() : Long.parseLong(String.valueOf(id));
                if (value == selectedId.longValue()) {
                    found = true;
                    break;
                }
            }
            if (!found) selectedId = null;
        }
        if (selectedId == null && !sessions.isEmpty()) {
            Map<String, Object> best = sessions.get(0);
            for (Map<String, Object> session : sessions) {
                if (longOf(session.get("token_total")) > longOf(best.get("token_total"))) {
                    best = session;
                }
            }
            Object id = best.get("id");
            selectedId = id instanceof Number ? ((Number) id).longValue() : Long.valueOf(String.valueOf(id));
        }
        result.put("selectedSessionId", selectedId);
        Map<String, Object> selectedSession = null;
        if (selectedId != null) {
            try {
                selectedSession = sessionChain(selectedId, null);
            } catch (RuntimeException ignored) {
                selectedSession = null;
            }
        }
        result.put("selectedSession", selectedSession);
        return result;
    }

    private Map<String, Object> resolveSkillCard(String slug) {
        // Observation detail only for platform-registered skills.
        List<Map<String, Object>> platform = jdbc.queryForList(
                "SELECT slug, name, category, description FROM skill WHERE slug=?", slug);
        if (platform.isEmpty()) return null;
        return platform.get(0);
    }

    /**
     * Version dropdown entries for one skill: published skill_version labels +
     * DISTINCT observation_step.skill_version_label.
     * Each item: {label, source, callCount}. Digest is not used for observation identity.
     */
    public List<Map<String, Object>> skillVersions(String slug) {
        Map<String, Map<String, Object>> byLabel = new LinkedHashMap<String, Map<String, Object>>();
        List<Map<String, Object>> observed = jdbc.queryForList(
                "SELECT TRIM(st.skill_version_label) AS label, MAX(st.skill_version_source) AS source, " +
                        "COUNT(*) AS call_count " +
                        "FROM observation_step st " +
                        "WHERE st.skill_slug=? AND st.type='skill' " +
                        "AND st.skill_version_label IS NOT NULL AND TRIM(st.skill_version_label) <> '' " +
                        "GROUP BY TRIM(st.skill_version_label) " +
                        "ORDER BY call_count DESC",
                slug);
        for (Map<String, Object> row : observed) {
            String label = row.get("label") == null ? null : String.valueOf(row.get("label")).trim();
            if (label == null || label.isEmpty()) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("label", label);
            item.put("source", row.get("source") == null ? "observed" : String.valueOf(row.get("source")));
            item.put("callCount", Integer.valueOf(intOf(row.get("call_count"))));
            byLabel.put(label.toLowerCase(Locale.ROOT), item);
        }

        List<Map<String, Object>> platform = jdbc.queryForList("SELECT id FROM skill WHERE slug=?", slug);
        if (!platform.isEmpty()) {
            Object skillId = platform.get(0).get("id");
            List<Map<String, Object>> published = jdbc.queryForList(
                    "SELECT version_label AS label FROM skill_version WHERE skill_id=? " +
                            "ORDER BY created_at DESC, id DESC", skillId);
            for (Map<String, Object> row : published) {
                String label = row.get("label") == null ? null : String.valueOf(row.get("label")).trim();
                if (label == null || label.isEmpty()) continue;
                String key = label.toLowerCase(Locale.ROOT);
                Map<String, Object> existing = byLabel.get(key);
                if (existing == null) {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("label", label);
                    item.put("source", null);
                    item.put("callCount", Integer.valueOf(0));
                    byLabel.put(key, item);
                }
            }
        }
        return new ArrayList<Map<String, Object>>(byLabel.values());
    }

    private static final int SESSION_CHAIN_MAX_TURNS = 80;
    private static final int SESSION_CHAIN_MAX_FIELD_CHARS = 4000;

    public Map<String, Object> sessionChain(long sessionId, String skillSlug) {
        List<Map<String, Object>> sessions = jdbc.queryForList(
                "SELECT sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, c.os, sess.started_at, sess.ended_at, " +
                        "COALESCE(NULLIF(MAX(sess.title), ''), MIN(stitle.title)) AS title, MIN(sraw.raw_title) AS raw_title " +
                        "FROM observation_session sess JOIN observation_client c ON c.id=sess.client_row_id " +
                        SESSION_TITLE_JOIN +
                        "WHERE sess.id=? GROUP BY sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, c.os, sess.started_at, sess.ended_at",
                sessionId);
        if (sessions.isEmpty()) throw new IllegalArgumentException("观测会话不存在");
        enrichSessionTitles(sessions);
        Map<String, Object> result = new LinkedHashMap<String, Object>(sessions.get(0));

        boolean filterSkill = skillSlug != null && !skillSlug.trim().isEmpty();
        String turnSql = "SELECT t.id, t.turn_index, t.started_at, LEFT(t.user_text, 2000) AS user_text FROM observation_turn t " +
                "WHERE t.session_id=?" +
                (filterSkill ? " AND EXISTS (SELECT 1 FROM observation_step st WHERE st.turn_id=t.id AND st.skill_slug=?)" : "") +
                " ORDER BY t.turn_index DESC LIMIT " + SESSION_CHAIN_MAX_TURNS;
        List<Map<String, Object>> turns = filterSkill
                ? jdbc.queryForList(turnSql, sessionId, skillSlug.trim())
                : jdbc.queryForList(turnSql, sessionId);
        if (turns.isEmpty()) {
            result.put("turns", new ArrayList<Map<String, Object>>());
            result.put("truncated", Boolean.TRUE);
            result.put("message", "会话暂无回合数据");
            return result;
        }

        // One query for all steps of the limited turns (avoids N+1 per turn).
        List<Long> turnIds = new ArrayList<Long>();
        Map<Long, Map<String, Object>> turnById = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> turn : turns) {
            Long turnId = ((Number) turn.get("id")).longValue();
            turnIds.add(turnId);
            turnById.put(turnId, turn);
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(turnIds.size(), "?"));
        List<Map<String, Object>> steps = jdbc.queryForList(
                "SELECT turn_id, step_id, seq, type, ts, skill_slug, payload FROM observation_step " +
                        "WHERE turn_id IN (" + placeholders + ") ORDER BY turn_id, seq, id",
                turnIds.toArray());
        Map<Long, List<Map<String, Object>>> stepsByTurn = new LinkedHashMap<Long, List<Map<String, Object>>>();
        for (Map<String, Object> step : steps) {
            Long turnId = ((Number) step.get("turn_id")).longValue();
            List<Map<String, Object>> list = stepsByTurn.get(turnId);
            if (list == null) {
                list = new ArrayList<Map<String, Object>>();
                stepsByTurn.put(turnId, list);
            }
            Map<String, Object> stepView = new LinkedHashMap<String, Object>(step);
            stepView.remove("turn_id");
            stepView.put("payload", truncatePayloadValue(parseJson(step.get("payload")), SESSION_CHAIN_MAX_FIELD_CHARS));
            list.add(stepView);
        }

        // Return chronological order.
        List<Map<String, Object>> orderedTurns = new ArrayList<Map<String, Object>>(turns);
        java.util.Collections.reverse(orderedTurns);
        List<Map<String, Object>> turnViews = new ArrayList<Map<String, Object>>();
        long[] sessionUsage = new long[] {0L, 0L, 0L, 0L, 0L, 0L}; // in, cr, cw, out, total, req
        for (Map<String, Object> turn : orderedTurns) {
            Long turnId = ((Number) turn.get("id")).longValue();
            List<Map<String, Object>> stepList = stepsByTurn.get(turnId) == null
                    ? new ArrayList<Map<String, Object>>()
                    : stepsByTurn.get(turnId);
            Map<String, Object> view = new LinkedHashMap<String, Object>(turn);
            view.put("steps", stepList);
            Map<String, Object> turnUsage = emptyUsageMap();
            for (Map<String, Object> step : stepList) {
                Object payloadObj = step.get("payload");
                Map<String, Object> usage = extractUsageFromPayload(payloadObj);
                if (usage == null) continue;
                addUsageInto(turnUsage, usage);
            }
            view.put("usage", turnUsage);
            view.put("token_total", turnUsage.get("total_tokens"));
            sessionUsage[0] += longOf(turnUsage.get("input_tokens"));
            sessionUsage[1] += longOf(turnUsage.get("cache_read_input_tokens"));
            sessionUsage[2] += longOf(turnUsage.get("cache_creation_input_tokens"));
            sessionUsage[3] += longOf(turnUsage.get("output_tokens"));
            sessionUsage[4] += longOf(turnUsage.get("total_tokens"));
            sessionUsage[5] += longOf(turnUsage.get("request_count"));
            turnViews.add(view);
        }
        Map<String, Object> sessionUsageMap = emptyUsageMap();
        sessionUsageMap.put("input_tokens", Long.valueOf(sessionUsage[0]));
        sessionUsageMap.put("cache_read_input_tokens", Long.valueOf(sessionUsage[1]));
        sessionUsageMap.put("cache_creation_input_tokens", Long.valueOf(sessionUsage[2]));
        sessionUsageMap.put("output_tokens", Long.valueOf(sessionUsage[3]));
        sessionUsageMap.put("total_tokens", Long.valueOf(sessionUsage[4]));
        sessionUsageMap.put("request_count", Long.valueOf(sessionUsage[5]));
        sessionUsageMap.put("inputTokens", Long.valueOf(sessionUsage[0]));
        sessionUsageMap.put("cacheReadTokens", Long.valueOf(sessionUsage[1]));
        sessionUsageMap.put("cacheWriteTokens", Long.valueOf(sessionUsage[2]));
        sessionUsageMap.put("outputTokens", Long.valueOf(sessionUsage[3]));
        sessionUsageMap.put("totalTokens", Long.valueOf(sessionUsage[4]));
        sessionUsageMap.put("requestCount", Long.valueOf(sessionUsage[5]));
        result.put("usage", sessionUsageMap);
        result.put("token_total", Long.valueOf(sessionUsage[4]));
        result.put("turns", turnViews);
        result.put("truncated", Boolean.valueOf(turns.size() >= SESSION_CHAIN_MAX_TURNS));
        if (turns.size() >= SESSION_CHAIN_MAX_TURNS) {
            result.put("message", "为保证性能，链路仅展示最近 " + SESSION_CHAIN_MAX_TURNS + " 个回合，超长字段已截断");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractUsageFromPayload(Object payloadObj) {
        if (!(payloadObj instanceof Map)) return null;
        Map<String, Object> payload = (Map<String, Object>) payloadObj;
        Object usageObj = payload.get("usage");
        if (usageObj == null) usageObj = payload.get("token_usage");
        if (!(usageObj instanceof Map)) return null;
        Map<String, Object> raw = (Map<String, Object>) usageObj;
        long input = longOf(raw.get("input_tokens") != null ? raw.get("input_tokens") : raw.get("inputTokens"));
        long cacheRead = longOf(firstNonNull(raw, "cache_read_input_tokens", "cacheReadTokens", "cached_input_tokens"));
        long cacheWrite = longOf(firstNonNull(raw, "cache_creation_input_tokens", "cacheWriteTokens", "cache_write_input_tokens"));
        long output = longOf(raw.get("output_tokens") != null ? raw.get("output_tokens") : raw.get("outputTokens"));
        long total = longOf(raw.get("total_tokens") != null ? raw.get("total_tokens") : raw.get("totalTokens"));
        if (total <= 0 && (input > 0 || cacheRead > 0 || cacheWrite > 0 || output > 0)) {
            total = input + cacheRead + cacheWrite + output;
        }
        long requests = longOf(raw.get("request_count") != null ? raw.get("request_count") : raw.get("requestCount"));
        if (total <= 0 && requests <= 0 && input <= 0 && output <= 0) return null;
        Map<String, Object> usage = emptyUsageMap();
        usage.put("input_tokens", Long.valueOf(input));
        usage.put("cache_read_input_tokens", Long.valueOf(cacheRead));
        usage.put("cache_creation_input_tokens", Long.valueOf(cacheWrite));
        usage.put("output_tokens", Long.valueOf(output));
        usage.put("total_tokens", Long.valueOf(total));
        usage.put("request_count", Long.valueOf(requests > 0 ? requests : 1));
        return usage;
    }

    private static Object firstNonNull(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.get(key) != null) return map.get(key);
        }
        return null;
    }

    private static Map<String, Object> emptyUsageMap() {
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        usage.put("input_tokens", Long.valueOf(0L));
        usage.put("cache_read_input_tokens", Long.valueOf(0L));
        usage.put("cache_creation_input_tokens", Long.valueOf(0L));
        usage.put("output_tokens", Long.valueOf(0L));
        usage.put("total_tokens", Long.valueOf(0L));
        usage.put("request_count", Long.valueOf(0L));
        return usage;
    }

    @SuppressWarnings("unchecked")
    private static void addUsageInto(Map<String, Object> target, Map<String, Object> delta) {
        for (String key : new String[] {
                "input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens",
                "output_tokens", "total_tokens", "request_count"
        }) {
            target.put(key, Long.valueOf(longOf(target.get(key)) + longOf(delta.get(key))));
        }
        target.put("inputTokens", target.get("input_tokens"));
        target.put("cacheReadTokens", target.get("cache_read_input_tokens"));
        target.put("cacheWriteTokens", target.get("cache_creation_input_tokens"));
        target.put("outputTokens", target.get("output_tokens"));
        target.put("totalTokens", target.get("total_tokens"));
        target.put("requestCount", target.get("request_count"));
    }

    @SuppressWarnings("unchecked")
    private Object truncatePayloadValue(Object value, int maxChars) {
        if (value == null) return null;
        if (value instanceof String) {
            String text = (String) value;
            if (text.length() <= maxChars) return text;
            return text.substring(0, maxChars) + "\n…[内容已截断，原 " + text.length() + " 字符]";
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object item : (List<Object>) value) out.add(truncatePayloadValue(item, maxChars));
            return out;
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                out.put(entry.getKey(), truncatePayloadValue(entry.getValue(), maxChars));
            }
            return out;
        }
        return value;
    }

    private Map<String, List<Map<String, Object>>> trendBySkill(String slug) {
        String sql = "SELECT st.skill_slug, (st.ts AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS count, " +
                usageSum("total_tokens", NON_ROLLUP_SKILL) + " AS tokens, " +
                usageSum("input_tokens", NON_ROLLUP_SKILL) + " AS input_tokens, " +
                usageSum("cache_read_input_tokens", NON_ROLLUP_SKILL) + " AS cache_read_tokens, " +
                usageSum("cache_creation_input_tokens", NON_ROLLUP_SKILL) + " AS cache_write_tokens, " +
                usageSum("output_tokens", NON_ROLLUP_SKILL) + " AS output_tokens " +
                "FROM observation_step st JOIN skill s ON s.slug=st.skill_slug " +
                "WHERE st.ts >= CURRENT_TIMESTAMP - INTERVAL '7 days' " +
                "AND (st.type='skill' OR (st.type IN ('tool','document') AND (st.payload->>'match') IS NOT NULL))";
        List<Object> args = new ArrayList<Object>();
        if (slug != null && !slug.trim().isEmpty()) {
            sql += " AND st.skill_slug=?";
            args.add(slug);
        }
        sql += " GROUP BY 1, 2";
        List<Map<String, Object>> rows = args.isEmpty() ? jdbc.queryForList(sql) : jdbc.queryForList(sql, args.toArray());
        Map<String, List<Map<String, Object>>> grouped = new HashMap<String, List<Map<String, Object>>>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("skill_slug"));
            List<Map<String, Object>> list = grouped.get(key);
            if (list == null) {
                list = new ArrayList<Map<String, Object>>();
                grouped.put(key, list);
            }
            Map<String, Object> point = new LinkedHashMap<String, Object>();
            point.put("day", String.valueOf(row.get("day")));
            point.put("count", row.get("count"));
            point.put("tokens", Long.valueOf(longOf(row.get("tokens"))));
            Map<String, Object> usage = new LinkedHashMap<String, Object>();
            usage.put("input_tokens", Long.valueOf(longOf(row.get("input_tokens"))));
            usage.put("cache_read_input_tokens", Long.valueOf(longOf(row.get("cache_read_tokens"))));
            usage.put("cache_creation_input_tokens", Long.valueOf(longOf(row.get("cache_write_tokens"))));
            usage.put("output_tokens", Long.valueOf(longOf(row.get("output_tokens"))));
            usage.put("total_tokens", Long.valueOf(longOf(row.get("tokens"))));
            point.put("usage", usage);
            list.add(point);
        }
        if (slug == null) {
            List<Map<String, Object>> all = jdbc.queryForList(
                    "SELECT (st.ts AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS count, " +
                            usageSum("total_tokens", NON_ROLLUP_SKILL) + " AS tokens " +
                            "FROM observation_step st JOIN skill s ON s.slug=st.skill_slug " +
                            "WHERE st.ts >= CURRENT_TIMESTAMP - INTERVAL '7 days' " +
                            "AND (st.type='skill' OR (st.type IN ('tool','document') AND (st.payload->>'match') IS NOT NULL)) " +
                            "GROUP BY 1");
            List<Map<String, Object>> allPoints = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> row : all) {
                Map<String, Object> point = new LinkedHashMap<String, Object>(row);
                point.put("tokens", Long.valueOf(longOf(row.get("tokens"))));
                allPoints.add(point);
            }
            grouped.put("__all__", allPoints);
        }
        return grouped;
    }

    private List<Map<String, Object>> fillTrend(List<Map<String, Object>> points) {
        Map<String, Map<String, Object>> byDay = new HashMap<String, Map<String, Object>>();
        if (points != null) {
            for (Map<String, Object> point : points) {
                byDay.put(String.valueOf(point.get("day")), point);
            }
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("day", day.toString());
            Map<String, Object> source = byDay.get(day.toString());
            Number count = source == null ? null : (Number) source.get("count");
            item.put("count", count == null ? Integer.valueOf(0) : count);
            long tokens = source == null ? 0L : longOf(source.get("tokens"));
            item.put("tokens", Long.valueOf(tokens));
            Object usage = source == null ? null : source.get("usage");
            if (usage instanceof Map) {
                item.put("usage", usage);
            } else {
                Map<String, Object> emptyUsage = new LinkedHashMap<String, Object>();
                emptyUsage.put("total_tokens", Long.valueOf(tokens));
                item.put("usage", emptyUsage);
            }
            result.add(item);
        }
        return result;
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    @SuppressWarnings("unchecked")
    private Object parseJson(Object value) {
        if (value == null) return Collections.emptyMap();
        String raw = value instanceof PGobject ? ((PGobject) value).getValue() : String.valueOf(value);
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyMap();
        try {
            return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignored) {
            try {
                return mapper.readValue(raw, Object.class);
            } catch (Exception ignoredAgain) {
                return raw;
            }
        }
    }
}
