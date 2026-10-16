package com.km.skillhub.skillhubquality;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * JdbcTemplate access to {@code skill_quality_report}: one row per analysis
 * run, newest row per (skill_id, version_id) is the active report. JSONB is
 * read back as text so the service can parse with its own ObjectMapper.
 */
@Repository
public class SkillQualityReportRepository {

    private final JdbcTemplate jdbc;

    public SkillQualityReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a report row; returns the generated id. */
    public Long insert(Long skillId, Long versionId, String l1Json, String l2Json, String l3Json,
                       Double overallScore, String conclusion, String methodNote, String ruleVersion) {
        return jdbc.queryForObject(
                "INSERT INTO skill_quality_report(skill_id, version_id, l1_json, l2_json, l3_json,"
                        + " overall_score, conclusion, method_note, rule_version)"
                        + " VALUES (?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?) RETURNING id",
                Long.class,
                skillId, versionId,
                l1Json == null ? "{}" : l1Json,
                l2Json == null ? "{}" : l2Json,
                l3Json == null ? "{}" : l3Json,
                overallScore, conclusion, methodNote, ruleVersion);
    }

    /** Newest report for one skill version, or null. */
    public Map<String, Object> latest(Long skillId, Long versionId) {
        if (skillId == null || versionId == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, skill_id, version_id,"
                        + " l1_json::text AS l1_json, l2_json::text AS l2_json, l3_json::text AS l3_json,"
                        + " overall_score, conclusion, method_note, rule_version, created_at"
                        + " FROM skill_quality_report"
                        + " WHERE skill_id=? AND version_id=?"
                        + " ORDER BY created_at DESC, id DESC LIMIT 1",
                skillId, versionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Latest report per version of one skill (newest 8 versions first), shaped
     * for the summary endpoint: version / analyzedAt / overallScore /
     * fileScore / wordingScore / effectScore. Versions without a report keep a
     * row with null scores (LEFT JOIN), matching kmplm.
     */
    public List<Map<String, Object>> summaryRows(Long skillId) {
        if (skillId == null) return java.util.Collections.emptyList();
        return jdbc.queryForList(
                "SELECT v.version_label AS version,"
                        + " r.created_at AS analyzed_at,"
                        + " r.overall_score AS overall_score,"
                        + " (r.l1_json->>'score')::numeric AS file_score,"
                        + " (r.l2_json->>'score')::numeric AS wording_score,"
                        + " (r.l3_json->>'score')::numeric AS effect_score"
                        + " FROM skill_version v"
                        + " LEFT JOIN skill_quality_report r ON r.id = ("
                        + "   SELECT latest.id FROM skill_quality_report latest"
                        + "   WHERE latest.skill_id = v.skill_id AND latest.version_id = v.id"
                        + "   ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1)"
                        + " WHERE v.skill_id=?"
                        + " ORDER BY v.created_at DESC, v.id DESC"
                        + " LIMIT 8",
                skillId);
    }

    /** Version label for a version row; null when unknown. */
    public String versionLabel(Long versionId) {
        if (versionId == null) return null;
        List<String> labels = jdbc.queryForList(
                "SELECT version_label FROM skill_version WHERE id=?", String.class, versionId);
        return labels.isEmpty() ? null : labels.get(0);
    }

    /**
     * Quality-report scoped read of {@code skill_file} content as UTF-8 text
     * (BYTEA decoded via convert_from). Falls back to byte decoding when a
     * payload is not valid UTF-8.
     */
    public List<Map<String, Object>> skillFileTexts(Long versionId) {
        if (versionId == null) return java.util.Collections.emptyList();
        try {
            return jdbc.queryForList(
                    "SELECT path, convert_from(content, 'UTF8') AS content"
                            + " FROM skill_file WHERE version_id=? ORDER BY path",
                    versionId);
        } catch (RuntimeException ex) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT path, content FROM skill_file WHERE version_id=? ORDER BY path",
                    versionId);
            for (Map<String, Object> row : rows) {
                Object raw = row.get("content");
                if (raw instanceof byte[]) {
                    row.put("content", new String((byte[]) raw, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            return rows;
        }
    }
}
