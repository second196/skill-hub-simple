package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cached analysis results.
 *
 * <p>Analysis is a side path: it never blocks ingest, and every result is reused until it
 * is explicitly recomputed. Rows are keyed by (slug, version_label, type, scope_key) so a
 * recompute overwrites in place. This is also what keeps an expensive judgement from being
 * paid twice for the same turn.
 */
@Repository
public class SkillAnalysisRepository {

    public static final String TYPE_PROBLEM = "problem_rule";
    public static final String TYPE_COVERAGE = "coverage";
    public static final String TYPE_VERDICT = "llm_verdict";
    public static final String TYPE_CONFLICT = "conflict";
    public static final String TYPE_PATH = "path_cost";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SkillAnalysisRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void upsert(String slug, String versionLabel, String type, String scopeKey, Object result, String status) {
        jdbc.update(
                "INSERT INTO skill_analysis (slug, version_label, analysis_type, scope_key, result, status) " +
                        "VALUES (?,?,?,?,?::jsonb,?) " +
                        "ON CONFLICT (slug, version_label, analysis_type, scope_key) DO UPDATE SET " +
                        "result=EXCLUDED.result, status=EXCLUDED.status, updated_at=CURRENT_TIMESTAMP",
                slug, nullToEmpty(versionLabel), type, scopeKey, writeJson(result),
                status == null ? "ok" : status);
    }

    public void replaceAll(String slug, String versionLabel, String type, List<Map<String, Object>> rows) {
        deleteType(slug, versionLabel, type);
        for (Map<String, Object> row : rows) {
            Object scope = row.get("scopeKey");
            upsert(slug, versionLabel, type, scope == null ? "" : String.valueOf(scope), row,
                    row.get("status") == null ? "ok" : String.valueOf(row.get("status")));
        }
    }

    public void deleteType(String slug, String versionLabel, String type) {
        jdbc.update("DELETE FROM skill_analysis WHERE slug=? AND version_label=? AND analysis_type=?",
                slug, nullToEmpty(versionLabel), type);
    }

    public void deleteAll(String slug, String versionLabel) {
        jdbc.update("DELETE FROM skill_analysis WHERE slug=? AND version_label=?",
                slug, nullToEmpty(versionLabel));
    }

    public List<Map<String, Object>> find(String slug, String versionLabel, String type) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT scope_key, result, status, updated_at FROM skill_analysis " +
                        "WHERE slug=? AND version_label=? AND analysis_type=? ORDER BY id",
                slug, nullToEmpty(versionLabel), type);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("scopeKey", String.valueOf(row.get("scope_key")));
            item.put("status", row.get("status"));
            item.put("updatedAt", asIsoString(row.get("updated_at")));
            Object parsed = parseJson(row.get("result"));
            Object result = parsed instanceof Map ? parsed : Collections.emptyMap();
            item.put("result", result);
            if (result instanceof Map) {
                // Flatten the payload so callers can read fields directly.
                @SuppressWarnings("unchecked")
                Map<String, Object> flat = new LinkedHashMap<String, Object>((Map<String, Object>) result);
                flat.putAll(item);
                out.add(flat);
                continue;
            }
            out.add(item);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> findOne(String slug, String versionLabel, String type, String scopeKey) {
        List<Map<String, Object>> rows = find(slug, versionLabel, type);
        for (Map<String, Object> row : rows) {
            if (String.valueOf(row.get("scopeKey")).equals(String.valueOf(scopeKey))) return row;
        }
        return null;
    }

    public int count(String slug, String versionLabel, String type) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM skill_analysis WHERE slug=? AND version_label=? AND analysis_type=?",
                Integer.class, slug, nullToEmpty(versionLabel), type);
        return value == null ? 0 : value.intValue();
    }

    private Object parseJson(Object value) {
        if (value == null) return null;
        String raw;
        if (value instanceof PGobject) {
            raw = ((PGobject) value).getValue();
        } else if (value instanceof byte[]) {
            raw = new String((byte[]) value, StandardCharsets.UTF_8);
        } else {
            raw = String.valueOf(value);
        }
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return mapper.readValue(raw, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Collections.emptyMap() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String asIsoString(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toInstant().toString();
        return String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
