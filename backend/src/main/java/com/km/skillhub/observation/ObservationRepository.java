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
        Long id = jdbc.queryForObject(
                "INSERT INTO observation_session (client_row_id, session_key, client_name, started_at, ended_at) " +
                        "VALUES (?,?,?,?,?) ON CONFLICT (client_row_id, session_key) DO UPDATE SET " +
                        "client_name=EXCLUDED.client_name, " +
                        "started_at=COALESCE(EXCLUDED.started_at, observation_session.started_at), " +
                        "ended_at=COALESCE(EXCLUDED.ended_at, observation_session.ended_at), " +
                        "updated_at=CURRENT_TIMESTAMP RETURNING id",
                Long.class, clientRowId, sessionKey, clientName, timestamp(startedAt), timestamp(endedAt));
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
        jdbc.update(
                "INSERT INTO observation_step (turn_id, step_id, seq, type, ts, skill_slug, payload) VALUES (?,?,?,?,?,?,?::jsonb) " +
                        "ON CONFLICT (turn_id, step_id) DO UPDATE SET " +
                        "seq=EXCLUDED.seq, type=EXCLUDED.type, ts=COALESCE(EXCLUDED.ts, observation_step.ts), " +
                        "skill_slug=COALESCE(EXCLUDED.skill_slug, observation_step.skill_slug), " +
                        "payload=CASE WHEN length(EXCLUDED.payload::text) >= length(observation_step.payload::text) " +
                        "THEN EXCLUDED.payload ELSE observation_step.payload END, " +
                        "updated_at=CURRENT_TIMESTAMP",
                turnId, stepId, seq, type, timestamp(ts), skillSlug, ObservationPayloads.forJsonb(payloadJson));
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

    public List<Map<String, Object>> listObservedSkills() {
        // Platform observation cards only: skill must exist in the platform skill table.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.slug, s.name, s.category, s.description, " +
                        "COUNT(*) FILTER (WHERE st.type='skill') AS skill_calls, " +
                        "COUNT(*) FILTER (WHERE st.type IN ('tool','document') AND (st.payload->>'match') IS NOT NULL) AS file_loads, " +
                        "COUNT(DISTINCT sess.id) AS session_count, " +
                        "COUNT(DISTINCT c.client_id) AS client_count, " +
                        "MAX(st.ts) AS last_used_at " +
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
            String slug = String.valueOf(row.get("slug"));
            Map<String, Object> quality = skillQualitySummary(slug);
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
        Map<String, Object> row = firstOrNull(jdbc.queryForList(
                "WITH skill_steps AS (" +
                        " SELECT st.id, st.turn_id, st.seq, st.payload, t.session_id" +
                        " FROM observation_step st JOIN observation_turn t ON t.id=st.turn_id" +
                        " WHERE st.skill_slug=? AND st.type='skill'" +
                        "   AND COALESCE(st.payload->>'rollup','false') <> 'true'" +
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
                        " (SELECT COUNT(*) FROM turn_loads WHERE loads >= 2) AS reload_turns" ,
                slug));
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
    }

    public Map<String, Object> skillQuality(String slug) {
        Map<String, Object> row = firstOrNull(jdbc.queryForList(
                "WITH skill_steps AS (" +
                        " SELECT st.id, st.turn_id, st.seq, st.payload, t.session_id" +
                        " FROM observation_step st JOIN observation_turn t ON t.id=st.turn_id" +
                        " WHERE st.skill_slug=? AND st.type='skill'" +
                        "   AND COALESCE(st.payload->>'rollup','false') <> 'true'" +
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
                slug));
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
        quality.put("pathDistribution", pathDistribution(slug));
        quality.put("evidence", skillEvidenceLevels(slug));
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
        Map<String, Object> row = firstOrNull(jdbc.queryForList(
                "WITH skill_steps AS (" +
                        " SELECT st.id, st.turn_id, st.seq, st.payload, t.session_id" +
                        " FROM observation_step st JOIN observation_turn t ON t.id=st.turn_id" +
                        " WHERE st.skill_slug=? AND st.type='skill'" +
                        "   AND COALESCE(st.payload->>'rollup','false') <> 'true'" +
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
                slug));
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
        return jdbc.queryForList(
                "WITH skill_steps AS (" +
                        " SELECT st.id, st.turn_id, st.seq, st.payload, t.session_id" +
                        " FROM observation_step st JOIN observation_turn t ON t.id=st.turn_id" +
                        " WHERE st.skill_slug=? AND st.type='skill'" +
                        "   AND COALESCE(st.payload->>'rollup','false') <> 'true'" +
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
                slug, Integer.valueOf(limit));
    }

    private List<Map<String, Object>> pathDistribution(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT COALESCE(payload->>'match','other') AS match_key, COUNT(*) AS count" +
                        " FROM observation_step" +
                        " WHERE skill_slug=? AND type='skill'" +
                        " GROUP BY 1 ORDER BY count DESC", slug);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int total = 0;
        for (Map<String, Object> row : rows) total += intOf(row.get("count"));
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            String key = String.valueOf(row.get("match_key"));
            int count = intOf(row.get("count"));
            item.put("key", key);
            item.put("label", pathLabel(key));
            item.put("count", Integer.valueOf(count));
            item.put("ratio", Double.valueOf(total == 0 ? 0d : round2((double) count / total)));
            result.add(item);
        }
        return result;
    }

    private static String pathLabel(String key) {
        if ("call".equals(key)) return "Skill 工具调用";
        if ("file".equals(key)) return "读 SKILL.md / 文件";
        if ("path".equals(key)) return "读Skill目录路径";
        if ("text".equals(key)) return "用户 /$slash 文本";
        return "其他";
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

    public Map<String, Object> sessionList(String clientId, Long sessionId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("clients", jdbc.queryForList(
                "SELECT c.client_id, c.hostname, c.os, COUNT(DISTINCT sess.id) AS session_count, MAX(c.last_seen_at) AS last_seen_at " +
                        "FROM observation_client c " +
                        "LEFT JOIN observation_session sess ON sess.client_row_id=c.id " +
                        "GROUP BY c.client_id, c.hostname, c.os ORDER BY last_seen_at DESC"));
        String sessionSql = "SELECT sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, " +
                "sess.started_at, sess.ended_at, COUNT(DISTINCT t.id) AS turn_count " +
                "FROM observation_session sess " +
                "JOIN observation_client c ON c.id=sess.client_row_id " +
                "LEFT JOIN observation_turn t ON t.session_id=sess.id";
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
        Map<String, Object> skill = resolveSkillCard(slug);
        if (skill == null) throw new IllegalArgumentException("Skill不存在");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("skill", skill);

        Map<String, Object> kpis = new LinkedHashMap<String, Object>();
        kpis.put("callCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM observation_step st " +
                        "WHERE st.skill_slug=? AND (st.type='skill' " +
                        "OR (st.type IN ('tool','document') AND (st.payload->>'match') IS NOT NULL))", Integer.class, slug));
        kpis.put("sessionCount", jdbc.queryForObject(
                "SELECT COUNT(DISTINCT sess.id) FROM observation_session sess " +
                        "JOIN observation_turn t ON t.session_id=sess.id " +
                        "JOIN observation_step st ON st.turn_id=t.id WHERE st.skill_slug=?", Integer.class, slug));
        kpis.put("clientCount", jdbc.queryForObject(
                "SELECT COUNT(DISTINCT c.client_id) FROM observation_client c " +
                        "JOIN observation_session sess ON sess.client_row_id=c.id " +
                        "JOIN observation_turn t ON t.session_id=sess.id " +
                        "JOIN observation_step st ON st.turn_id=t.id WHERE st.skill_slug=?", Integer.class, slug));
        kpis.put("turnCount", jdbc.queryForObject(
                "SELECT COUNT(DISTINCT t.id) FROM observation_turn t " +
                        "JOIN observation_step st ON st.turn_id=t.id WHERE st.skill_slug=?", Integer.class, slug));
        result.put("kpis", kpis);
        result.put("trend", fillTrend(trendBySkill(slug).get(slug)));
        result.put("quality", skillQuality(slug));
        result.put("problemSessions", skillProblemSessions(slug, 20));

        result.put("clients", jdbc.queryForList(
                "SELECT c.client_id, c.hostname, c.os, COUNT(DISTINCT sess.id) AS session_count, MAX(c.last_seen_at) AS last_seen_at " +
                        "FROM observation_client c " +
                        "JOIN observation_session sess ON sess.client_row_id=c.id " +
                        "JOIN observation_turn t ON t.session_id=sess.id " +
                        "JOIN observation_step st ON st.turn_id=t.id " +
                        "WHERE st.skill_slug=? GROUP BY c.client_id, c.hostname, c.os ORDER BY last_seen_at DESC", slug));

        String sessionSql = "SELECT sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, " +
                "sess.started_at, sess.ended_at, COUNT(DISTINCT t.id) AS turn_count " +
                "FROM observation_session sess " +
                "JOIN observation_client c ON c.id=sess.client_row_id " +
                "JOIN observation_turn t ON t.session_id=sess.id " +
                "JOIN observation_step st ON st.turn_id=t.id " +
                "WHERE st.skill_slug=?";
        List<Object> args = new ArrayList<Object>();
        args.add(slug);
        if (clientId != null && !clientId.trim().isEmpty()) {
            sessionSql += " AND c.client_id=?";
            args.add(clientId.trim());
        }
        sessionSql += " GROUP BY sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, sess.started_at, sess.ended_at " +
                "ORDER BY sess.started_at DESC NULLS LAST, sess.id DESC LIMIT 100";
        List<Map<String, Object>> sessions = jdbc.queryForList(sessionSql, args.toArray());
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
            Object id = sessions.get(0).get("id");
            selectedId = id instanceof Number ? ((Number) id).longValue() : Long.valueOf(String.valueOf(id));
        }
        result.put("selectedSessionId", selectedId);
        // Always load the full session chain so the detail page keeps complete turn text.
        result.put("selectedSession", selectedId == null ? null : sessionChain(selectedId, null));
        return result;
    }

    private Map<String, Object> resolveSkillCard(String slug) {
        // Observation detail only for platform-registered skills.
        List<Map<String, Object>> platform = jdbc.queryForList(
                "SELECT slug, name, category, description FROM skill WHERE slug=?", slug);
        if (platform.isEmpty()) return null;
        return platform.get(0);
    }

    private static final int SESSION_CHAIN_MAX_TURNS = 80;
    private static final int SESSION_CHAIN_MAX_FIELD_CHARS = 4000;

    public Map<String, Object> sessionChain(long sessionId, String skillSlug) {
        List<Map<String, Object>> sessions = jdbc.queryForList(
                "SELECT sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, c.os, sess.started_at, sess.ended_at " +
                        "FROM observation_session sess JOIN observation_client c ON c.id=sess.client_row_id WHERE sess.id=?",
                sessionId);
        if (sessions.isEmpty()) throw new IllegalArgumentException("观测会话不存在");
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
        for (Map<String, Object> turn : orderedTurns) {
            Long turnId = ((Number) turn.get("id")).longValue();
            Map<String, Object> view = new LinkedHashMap<String, Object>(turn);
            view.put("steps", stepsByTurn.get(turnId) == null ? new ArrayList<Map<String, Object>>() : stepsByTurn.get(turnId));
            turnViews.add(view);
        }
        result.put("turns", turnViews);
        result.put("truncated", Boolean.valueOf(turns.size() >= SESSION_CHAIN_MAX_TURNS));
        if (turns.size() >= SESSION_CHAIN_MAX_TURNS) {
            result.put("message", "为保证性能，链路仅展示最近 " + SESSION_CHAIN_MAX_TURNS + " 个回合，超长字段已截断");
        }
        return result;
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
        String sql = "SELECT st.skill_slug, (st.ts AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS count " +
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
            list.add(point);
        }
        if (slug == null) {
            List<Map<String, Object>> all = jdbc.queryForList(
                    "SELECT (st.ts AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS count " +
                            "FROM observation_step st JOIN skill s ON s.slug=st.skill_slug " +
                            "WHERE st.ts >= CURRENT_TIMESTAMP - INTERVAL '7 days' " +
                            "AND (st.type='skill' OR (st.type IN ('tool','document') AND (st.payload->>'match') IS NOT NULL)) " +
                            "GROUP BY 1");
            grouped.put("__all__", all);
        }
        return grouped;
    }

    private List<Map<String, Object>> fillTrend(List<Map<String, Object>> points) {
        Map<String, Number> byDay = new HashMap<String, Number>();
        if (points != null) {
            for (Map<String, Object> point : points) {
                byDay.put(String.valueOf(point.get("day")), (Number) point.get("count"));
            }
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("day", day.toString());
            Number count = byDay.get(day.toString());
            item.put("count", count == null ? Integer.valueOf(0) : count);
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
