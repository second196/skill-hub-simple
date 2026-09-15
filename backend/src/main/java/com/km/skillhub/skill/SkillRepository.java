package com.km.skillhub.skill;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
public class SkillRepository {
    private final JdbcTemplate jdbc;

    public SkillRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> list(String query, String category, String status) {
        return list(query, category, status, false);
    }

    public List<Map<String, Object>> list(String query, String category, String status, boolean includeOffline) {
        String sql = "SELECT s.id,s.slug,s.name,s.description,s.category,s.status,s.download_count,s.updated_at," +
                "v.version_label,v.version_digest FROM skill s JOIN LATERAL (SELECT version_label,version_digest " +
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
        return jdbc.queryForList(sql, args.toArray());
    }

    public List<String> categories() {
        return jdbc.queryForList("SELECT DISTINCT category FROM skill ORDER BY category", String.class);
    }

    public Map<String, Object> detail(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT s.id,s.slug,s.name,s.description,s.category,s.status,s.download_count,s.created_at,s.updated_at," +
                "v.id AS version_id,v.version_label,v.version_digest,v.created_at AS version_created_at FROM skill s " +
                "JOIN LATERAL (SELECT * FROM skill_version WHERE skill_id=s.id ORDER BY created_at DESC,id DESC LIMIT 1) v ON true WHERE s.slug=?", slug);
        if (rows.isEmpty()) throw new IllegalArgumentException("Skill不存在");
        Map<String, Object> result = rows.get(0);
        result.put("versions", jdbc.queryForList("SELECT version_label,version_digest,created_at FROM skill_version WHERE skill_id=? ORDER BY created_at DESC,id DESC", result.get("id")));
        return result;
    }

    public List<Map<String, Object>> files(String slug, String digest) {
        Long versionId = versionId(slug, digest);
        return jdbc.queryForList("SELECT path,content_type,size_bytes,content_digest FROM skill_file WHERE version_id=? ORDER BY path", versionId);
    }

    public Map<String, Object> file(String slug, String digest, String path) {
        Long versionId = versionId(slug, digest);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT path,content,content_type,size_bytes FROM skill_file WHERE version_id=? AND path=?", versionId, path);
        if (rows.isEmpty()) throw new IllegalArgumentException("文件不存在");
        return rows.get(0);
    }

    public Long versionId(String slug, String digest) {
        String sql = "SELECT v.id FROM skill_version v JOIN skill s ON s.id=v.skill_id WHERE s.slug=?";
        Object[] args;
        if (digest == null || digest.trim().isEmpty()) {
            sql += " ORDER BY v.created_at DESC,v.id DESC LIMIT 1";
            args = new Object[] { slug };
        } else {
            sql += " AND v.version_digest=?";
            args = new Object[] { slug, digest };
        }
        List<Long> ids = jdbc.queryForList(sql, Long.class, args);
        if (ids.isEmpty()) throw new IllegalArgumentException("Skill版本不存在");
        return ids.get(0);
    }

    @Transactional
    public Map<String, Object> save(SkillPackage value, String category) {
        String slug = uniqueSlug(value.getName());
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id,slug FROM skill WHERE LOWER(name)=LOWER(?)", value.getName());
        Long skillId;
        if (existing.isEmpty()) {
            skillId = jdbc.queryForObject(
                    "INSERT INTO skill(slug,name,description,category) VALUES (?,?,?,?) RETURNING id",
                    Long.class, slug, value.getName(), value.getDescription(), category);
        } else {
            skillId = ((Number) existing.get(0).get("id")).longValue();
            slug = String.valueOf(existing.get(0).get("slug"));
            jdbc.update("UPDATE skill SET description=?,category=?,status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id=?", value.getDescription(), category, skillId);
        }
        List<Map<String, Object>> sameVersion = jdbc.queryForList("SELECT version_digest FROM skill_version WHERE skill_id=? AND version_label=?", skillId, value.getVersion());
        if (!sameVersion.isEmpty()) {
            if (!value.getDigest().equals(sameVersion.get(0).get("version_digest"))) throw new IllegalArgumentException("同一版本号已存在不同内容");
            return detail(slug);
        }
        Long versionId = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label,version_digest) VALUES (?,?,?) RETURNING id",
                Long.class, skillId, value.getVersion(), value.getDigest());
        for (SkillPackage.FileEntry file : value.getFiles()) {
            jdbc.update("INSERT INTO skill_file(version_id,path,content,content_type,size_bytes,content_digest) VALUES (?,?,?,?,?,?)",
                    versionId, file.getPath(), file.getContent(), file.getContentType(), file.getContent().length, file.getDigest());
        }
        jdbc.update("UPDATE skill SET updated_at=CURRENT_TIMESTAMP WHERE id=?", skillId);
        return detail(slug);
    }

    public void offline(String slug) {
        if (jdbc.update("UPDATE skill SET status='OFFLINE',updated_at=CURRENT_TIMESTAMP WHERE slug=?", slug) != 1) throw new IllegalArgumentException("Skill不存在");
    }

    @Transactional
    public void delete(String slug) {
        if (jdbc.update("DELETE FROM skill WHERE slug=?", slug) != 1) throw new IllegalArgumentException("Skill不存在");
    }

    public void incrementDownloadCount(String slug) {
        if (jdbc.update("UPDATE skill SET download_count=download_count+1 WHERE slug=?", slug) != 1) {
            throw new IllegalArgumentException("Skill不存在");
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.trim().isEmpty()) return null;
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"OFFLINE".equals(normalized)) {
            throw new IllegalArgumentException("Skill状态只能是 ACTIVE 或 OFFLINE");
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
