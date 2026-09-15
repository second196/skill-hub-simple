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
        // callCount = skill calls + file loads attributed to that platform skill.
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
            item.put("trend", fillTrend(trends.get(String.valueOf(row.get("slug")))));
            result.add(item);
        }
        return result;
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
        if (skill == null) throw new IllegalArgumentException("技能不存在");
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
                "ORDER BY sess.started_at DESC NULLS LAST, sess.id DESC";
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

    public Map<String, Object> sessionChain(long sessionId, String skillSlug) {
        List<Map<String, Object>> sessions = jdbc.queryForList(
                "SELECT sess.id, sess.session_key, sess.client_name, c.client_id, c.hostname, c.os, sess.started_at, sess.ended_at " +
                        "FROM observation_session sess JOIN observation_client c ON c.id=sess.client_row_id WHERE sess.id=?",
                sessionId);
        if (sessions.isEmpty()) throw new IllegalArgumentException("观测会话不存在");
        Map<String, Object> result = new LinkedHashMap<String, Object>(sessions.get(0));
        List<Map<String, Object>> turns;
        if (skillSlug == null || skillSlug.trim().isEmpty()) {
            turns = jdbc.queryForList(
                    "SELECT t.id, t.turn_index, t.started_at, t.user_text FROM observation_turn t " +
                            "WHERE t.session_id=? ORDER BY t.turn_index",
                    sessionId);
        } else {
            turns = jdbc.queryForList(
                    "SELECT t.id, t.turn_index, t.started_at, t.user_text FROM observation_turn t " +
                            "WHERE t.session_id=? AND EXISTS (SELECT 1 FROM observation_step st WHERE st.turn_id=t.id AND st.skill_slug=?) " +
                            "ORDER BY t.turn_index",
                    sessionId, skillSlug);
        }
        List<Map<String, Object>> turnViews = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> turn : turns) {
            Map<String, Object> view = new LinkedHashMap<String, Object>(turn);
            Long turnId = ((Number) turn.get("id")).longValue();
            List<Map<String, Object>> steps = jdbc.queryForList(
                    "SELECT step_id, seq, type, ts, skill_slug, payload FROM observation_step WHERE turn_id=? ORDER BY seq, id",
                    turnId);
            List<Map<String, Object>> stepViews = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> step : steps) {
                Map<String, Object> stepView = new LinkedHashMap<String, Object>(step);
                stepView.put("payload", parseJson(step.get("payload")));
                stepViews.add(stepView);
            }
            view.put("steps", stepViews);
            turnViews.add(view);
        }
        result.put("turns", turnViews);
        return result;
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
