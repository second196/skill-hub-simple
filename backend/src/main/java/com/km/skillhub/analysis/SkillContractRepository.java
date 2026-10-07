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
 * Stores expected-step contracts keyed by (slug, version_label).
 *
 * <p>The contract is a shared asset: it is parsed once and every uploaded trace reuses
 * it, so the per-analysis cost does not grow with the number of clients.
 */
@Repository
public class SkillContractRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SkillContractRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Files of one skill version, in package order. */
    public List<SkillContractExtractor.Source> filesForVersion(String slug, String versionLabel) {
        Long versionId = versionId(slug, versionLabel);
        if (versionId == null) return Collections.emptyList();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT path, content FROM skill_file WHERE version_id=? ORDER BY path", versionId);
        List<SkillContractExtractor.Source> sources = new ArrayList<SkillContractExtractor.Source>();
        for (Map<String, Object> row : rows) {
            sources.add(new SkillContractExtractor.Source(
                    row.get("path") == null ? "" : String.valueOf(row.get("path")),
                    asText(row.get("content"))));
        }
        return sources;
    }

    /** Latest published version label for a slug, or null when the skill is unknown. */
    public String latestVersionLabel(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT v.version_label FROM skill_version v JOIN skill s ON s.id=v.skill_id " +
                        "WHERE s.slug=? ORDER BY v.created_at DESC, v.id DESC LIMIT 1", slug);
        if (rows.isEmpty()) return null;
        Object label = rows.get(0).get("version_label");
        return label == null ? null : String.valueOf(label);
    }

    public boolean exists(String slug, String versionLabel) {
        return !jdbc.queryForList(
                "SELECT 1 FROM skill_contract WHERE slug=? AND version_label=?",
                slug, nullToEmpty(versionLabel)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> findContract(String slug, String versionLabel) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT contract, parse_method, parse_meta, source_path, raw_hash, updated_at " +
                        "FROM skill_contract WHERE slug=? AND version_label=?",
                slug, nullToEmpty(versionLabel));
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("slug", slug);
        out.put("versionLabel", nullToEmpty(versionLabel));
        out.put("parseMethod", row.get("parse_method"));
        out.put("sourcePath", row.get("source_path"));
        out.put("updatedAt", asIsoString(row.get("updated_at")));
        Object contract = parseJson(row.get("contract"));
        out.put("contract", contract == null ? Collections.emptyMap() : contract);
        Object meta = parseJson(row.get("parse_meta"));
        out.put("parseMeta", meta == null ? Collections.emptyMap() : meta);
        int stepCount = 0;
        if (contract instanceof Map) {
            Object steps = ((Map<String, Object>) contract).get("steps");
            if (steps instanceof List) stepCount = ((List<Object>) steps).size();
        }
        out.put("stepCount", Integer.valueOf(stepCount));
        out.put("hasContract", Boolean.valueOf(stepCount > 0));
        return out;
    }

    public void upsert(String slug, String versionLabel, SkillContractExtractor.Result result) {
        String json = writeJson(result.getContract());
        String metaJson = writeJson(result.getMeta());
        jdbc.update(
                "INSERT INTO skill_contract (slug, version_label, source_path, raw_hash, contract, parse_method, parse_meta) " +
                        "VALUES (?,?,?,?,?::jsonb,?,?::jsonb) " +
                        "ON CONFLICT (slug, version_label) DO UPDATE SET " +
                        "source_path=EXCLUDED.source_path, raw_hash=EXCLUDED.raw_hash, " +
                        "contract=EXCLUDED.contract, parse_method=EXCLUDED.parse_method, " +
                        "parse_meta=EXCLUDED.parse_meta, updated_at=CURRENT_TIMESTAMP",
                slug, nullToEmpty(versionLabel), result.getSourcePath(), result.getRawHash(),
                json, result.getParseMethod(), metaJson);
    }

    /** All contracts currently stored for a slug (one per observed version). */
    public List<String> contractVersionLabels(String slug) {
        return jdbc.queryForList(
                "SELECT version_label FROM skill_contract WHERE slug=? ORDER BY version_label", String.class, slug);
    }

    public Long versionId(String slug, String versionLabel) {
        String label = com.km.skillhub.skill.SkillVersions.normalizeVersionLabel(versionLabel);
        List<Long> ids;
        if (label == null || label.isEmpty()) {
            ids = jdbc.queryForList(
                    "SELECT v.id FROM skill_version v JOIN skill s ON s.id=v.skill_id " +
                            "WHERE s.slug=? ORDER BY v.created_at DESC, v.id DESC LIMIT 1",
                    Long.class, slug);
        } else {
            ids = jdbc.queryForList(
                    "SELECT v.id FROM skill_version v JOIN skill s ON s.id=v.skill_id " +
                            "WHERE s.slug=? AND TRIM(v.version_label)=?",
                    Long.class, slug, label);
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static String asText(Object value) {
        if (value == null) return "";
        if (value instanceof byte[]) return new String((byte[]) value, StandardCharsets.UTF_8);
        if (value instanceof PGobject) {
            String text = ((PGobject) value).getValue();
            return text == null ? "" : text;
        }
        return String.valueOf(value);
    }

    private Object parseJson(Object value) {
        if (value == null) return null;
        String raw = asText(value);
        if (raw.trim().isEmpty()) return null;
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
