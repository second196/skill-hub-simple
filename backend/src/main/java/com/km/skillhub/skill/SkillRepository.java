package com.km.skillhub.skill;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SkillRepository {
    private static final String DEFAULT_CATEGORY = "其他";

    private final JdbcTemplate jdbc;

    public SkillRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> list(String query, String category, String status) {
        return list(query, category, status, false);
    }

    public List<Map<String, Object>> list(String query, String category, String status, boolean includeOffline) {
        String sql = "SELECT s.id,s.slug,s.name,s.description,s.category,s.status,s.download_count,s.updated_at," +
                "v.version_label FROM skill s JOIN LATERAL (SELECT version_label " +
                "FROM skill_version WHERE skill_id=s.id ORDER BY created_at DESC,id DESC LIMIT 1) v ON true WHERE 1=1";
        java.util.ArrayList<Object> args = new java.util.ArrayList<Object>();
        String normalizedStatus = normalizeStatus(status);
        if (normalizedStatus == null && !includeOffline) normalizedStatus = "ACTIVE";
        if (normalizedStatus != null) {
            sql += " AND s.status=?";
            args.add(normalizedStatus);
        }
        if (query != null && !query.trim().isEmpty()) { sql += " AND (LOWER(s.name) LIKE ? OR LOWER(s.description) LIKE ? OR LOWER(s.slug) LIKE ?)"; String q = "%" + query.trim().toLowerCase() + "%"; args.add(q); args.add(q); args.add(q); }
        if (category != null && !category.trim().isEmpty()) { sql += " AND s.category=?"; args.add(category.trim()); }
        sql += " ORDER BY s.updated_at DESC,s.id DESC";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            List<String> labels = jdbc.queryForList(
                    "SELECT version_label FROM skill_version WHERE skill_id=? ORDER BY created_at DESC,id DESC",
                    String.class, id);
            List<String> normalized = new java.util.ArrayList<String>();
            for (String label : labels) {
                String value = SkillVersions.normalizeVersionLabel(label);
                if (value != null) normalized.add(value);
            }
            row.put("version_labels", normalized);
            row.put("versionLabels", normalized);
        }
        return rows;
    }

    public List<String> categories() {
        return jdbc.queryForList("SELECT DISTINCT category FROM skill ORDER BY category", String.class);
    }

    public Map<String, Object> detail(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT s.id,s.slug,s.name,s.description,s.category,s.status,s.download_count,s.created_at,s.updated_at," +
                "v.id AS version_id,v.version_label,v.created_at AS version_created_at FROM skill s " +
                "JOIN LATERAL (SELECT * FROM skill_version WHERE skill_id=s.id ORDER BY created_at DESC,id DESC LIMIT 1) v ON true WHERE s.slug=?", slug);
        if (rows.isEmpty()) throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
        Map<String, Object> result = rows.get(0);
        result.put("versions", jdbc.queryForList(
                "SELECT version_label,created_at FROM skill_version WHERE skill_id=? ORDER BY created_at DESC,id DESC",
                result.get("id")));
        return result;
    }

    public List<Map<String, Object>> files(String slug, String version) {
        Long versionId = versionId(slug, version);
        return jdbc.queryForList("SELECT path,content_type,size_bytes FROM skill_file WHERE version_id=? ORDER BY path", versionId);
    }

    public Map<String, Object> file(String slug, String version, String path) {
        Long versionId = versionId(slug, version);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT path,content,content_type,size_bytes FROM skill_file WHERE version_id=? AND path=?", versionId, path);
        if (rows.isEmpty()) throw new SkillApiException("文件不存在", SkillApiException.CODE_VALIDATION_ERROR);
        return rows.get(0);
    }

    /** Resolve version row by SemVer version_label; blank version means latest. */
    public Long versionId(String slug, String version) {
        String sql = "SELECT v.id FROM skill_version v JOIN skill s ON s.id=v.skill_id WHERE s.slug=?";
        Object[] args;
        String label = SkillVersions.normalizeVersionLabel(version);
        if (label == null || label.isEmpty()) {
            sql += " ORDER BY v.created_at DESC,v.id DESC LIMIT 1";
            args = new Object[] { slug };
        } else {
            if (!SkillVersions.isSemVer(label)) {
                throw new SkillApiException(
                        "version 必须使用包内声明的语义化版本号（如 1.0.0）",
                        SkillApiException.CODE_VERSION_INVALID);
            }
            sql += " AND TRIM(v.version_label)=?";
            args = new Object[] { slug, label };
        }
        List<Long> ids = jdbc.queryForList(sql, Long.class, args);
        if (ids.isEmpty()) throw new SkillApiException("Skill版本不存在", SkillApiException.CODE_VERSION_NOT_FOUND);
        return ids.get(0);
    }

    @Transactional
    public Map<String, Object> save(SkillPackage value, String requestCategory) {
        String slug = uniqueSlug(value.getName());
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id,slug,category FROM skill WHERE LOWER(name)=LOWER(?)", value.getName());
        Long skillId;
        String effectiveCategory;
        if (existing.isEmpty()) {
            effectiveCategory = resolveCategory(value.getCategory(), null, requestCategory);
            skillId = jdbc.queryForObject(
                    "INSERT INTO skill(slug,name,description,category) VALUES (?,?,?,?) RETURNING id",
                    Long.class, slug, value.getName(), value.getDescription(), effectiveCategory);
        } else {
            skillId = ((Number) existing.get(0).get("id")).longValue();
            slug = String.valueOf(existing.get(0).get("slug"));
            String existingCategory = existing.get(0).get("category") == null
                    ? null : String.valueOf(existing.get(0).get("category"));
            // Throws SkillApiException on VERSION_EXISTS / VERSION_BUMP_REQUIRED.
            // Returns false when the exact version already exists (idempotent no-op after rejection is not used —
            // immutable versions always reject re-upload of the same label).
            validateVersionAgainstHistory(skillId, value.getVersion());
            effectiveCategory = resolveCategory(value.getCategory(), existingCategory, requestCategory);
            jdbc.update("UPDATE skill SET description=?,category=?,status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    value.getDescription(), effectiveCategory, skillId);
        }
        Long versionId = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, skillId,
                SkillVersions.normalizeVersionLabel(value.getVersion()));
        for (SkillPackage.FileEntry file : value.getFiles()) {
            jdbc.update("INSERT INTO skill_file(version_id,path,content,content_type,size_bytes,content_digest) VALUES (?,?,?,?,?,?)",
                    versionId, file.getPath(), file.getContent(), file.getContentType(), file.getContent().length, file.getContentDigest());
        }
        jdbc.update("UPDATE skill SET updated_at=CURRENT_TIMESTAMP WHERE id=?", skillId);
        return detail(slug);
    }

    /**
     * Category priority: package metadata &gt; existing platform skill &gt; CLI request &gt; 其他.
     */
    static String resolveCategory(String packageCategory, String existingCategory, String requestCategory) {
        if (isNotBlank(packageCategory)) return packageCategory.trim();
        if (isNotBlank(existingCategory)) return existingCategory.trim();
        if (isNotBlank(requestCategory)) return requestCategory.trim();
        return DEFAULT_CATEGORY;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Enforce immutable SemVer identity when the skill already exists.
     * Version identity is version_label only — content digest is not used.
     *
     * @throws SkillApiException VERSION_EXISTS / VERSION_BUMP_REQUIRED
     */
    private void validateVersionAgainstHistory(Long skillId, String versionLabel) {
        String incomingLabel = SkillVersions.normalizeVersionLabel(versionLabel);
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version_label FROM skill_version WHERE skill_id=?", skillId);
        List<String> labels = labelsOf(history);
        String maxFormal = SkillVersions.latestFormalVersion(labels);
        for (String existingLabel : labels) {
            String existingNormalized = SkillVersions.normalizeVersionLabel(existingLabel);
            if (incomingLabel != null && incomingLabel.equalsIgnoreCase(existingNormalized)) {
                String suggested = SkillVersions.suggestNextVersion(maxFormal);
                throw new SkillApiException(
                        "版本号 " + incomingLabel + " 在平台上已存在且不可覆盖（VERSION_EXISTS）。"
                                + "请将包内 version 提升到 ≥ " + suggested + " 后再上传",
                        SkillApiException.CODE_VERSION_EXISTS,
                        SkillApiException.versionExistsDetails(incomingLabel, maxFormal, suggested));
            }
        }
        if (maxFormal != null && SkillVersions.isSemVer(incomingLabel)
                && SkillVersions.compareSemVer(incomingLabel, maxFormal) <= 0) {
            String suggested = SkillVersions.suggestNextVersion(maxFormal);
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("version", incomingLabel);
            details.put("maxFormal", maxFormal);
            details.put("suggestedNextVersion", suggested);
            details.put("hint",
                    "包内 version 必须大于平台当前最新正式版。"
                            + "请修改 Skill 包内 version 后再上传（单 Skill：SKILL.md frontmatter；复合包：package.json）。");
            throw new SkillApiException(
                    "Skill 包内 version（" + incomingLabel + "）未超过平台最新版本（" + maxFormal
                            + "），请提升到 ≥ " + suggested + " 后再上传（VERSION_BUMP_REQUIRED）",
                    SkillApiException.CODE_VERSION_BUMP_REQUIRED,
                    details);
        }
    }

    private static List<String> labelsOf(List<Map<String, Object>> history) {
        java.util.ArrayList<String> labels = new java.util.ArrayList<String>();
        for (Map<String, Object> row : history) {
            Object value = row.get("version_label");
            if (value != null) labels.add(String.valueOf(value));
        }
        return labels;
    }

    public void offline(String slug) {
        if (jdbc.update("UPDATE skill SET status='OFFLINE',updated_at=CURRENT_TIMESTAMP WHERE slug=?", slug) != 1) {
            throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
        }
    }

    @Transactional
    public void delete(String slug) {
        if (jdbc.update("DELETE FROM skill WHERE slug=?", slug) != 1) {
            throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
        }
    }

    public void incrementDownloadCount(String slug) {
        if (jdbc.update("UPDATE skill SET download_count=download_count+1 WHERE slug=?", slug) != 1) {
            throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.trim().isEmpty()) return null;
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"OFFLINE".equals(normalized)) {
            throw new SkillApiException("Skill状态只能是 ACTIVE 或 OFFLINE", SkillApiException.CODE_VALIDATION_ERROR);
        }
        return normalized;
    }

    private String uniqueSlug(String name) {
        String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "skill-" + SkillPackageParser.sha256(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 8);
        String slug = base;
        int suffix = 2;
        while (!jdbc.queryForList("SELECT id FROM skill WHERE slug=? AND LOWER(name)<>LOWER(?)", slug, name).isEmpty()) slug = base + "-" + suffix++;
        return slug;
    }
}
