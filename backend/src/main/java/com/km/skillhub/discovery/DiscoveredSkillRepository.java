package com.km.skillhub.discovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DiscoveredSkillRepository {
    private final JdbcTemplate jdbc;

    public DiscoveredSkillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> list(String query, String category, String source, String sort, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        StringBuilder where = new StringBuilder(" WHERE visibility_status='ACTIVE'");
        List<Object> args = new ArrayList<Object>();
        if (query != null && !query.trim().isEmpty()) {
            where.append(" AND (LOWER(name) LIKE ? OR LOWER(description) LIKE ? OR LOWER(source_repository) LIKE ? OR LOWER(source_owner) LIKE ?)");
            String q = "%" + query.trim().toLowerCase() + "%";
            Collections.addAll(args, q, q, q, q);
        }
        if (category != null && !category.trim().isEmpty()) {
            where.append(" AND category=?");
            args.add(category.trim());
        }
        if (source != null && !source.trim().isEmpty()) {
            where.append(" AND source_type=?");
            args.add(source.trim().toUpperCase());
        }
        String order = sortClause(sort);
        Number total = jdbc.queryForObject("SELECT COUNT(*) FROM discovered_skill" + where, Number.class, args.toArray());
        List<Object> listArgs = new ArrayList<Object>(args);
        listArgs.add(safePageSize);
        listArgs.add((safePage - 1) * safePageSize);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT id,source_type,source_owner,source_repository,source_branch,source_path,source_url,install_url,package_type,name,description,category,supported_agents,github_stars,github_forks,external_install_count,discovery_download_count,trend_score,quality_score,trust_level,license,source_updated_at,last_synced_at FROM discovered_skill"
                        + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                listArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total.longValue());
        result.put("page", safePage);
        result.put("pageSize", safePageSize);
        return result;
    }

    public List<String> categories() {
        return jdbc.queryForList("SELECT DISTINCT category FROM discovered_skill WHERE visibility_status='ACTIVE' ORDER BY category", String.class);
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,source_type,source_owner,source_repository,source_branch,source_path,source_url,install_url,package_type,name,description,category,supported_agents,github_stars,github_forks,external_install_count,discovery_download_count,trend_score,quality_score,trust_level,license,source_updated_at,last_synced_at FROM discovered_skill WHERE id=?",
                id);
        if (rows.isEmpty()) throw new IllegalArgumentException("发现技能不存在");
        Map<String, Object> result = new LinkedHashMap<String, Object>(rows.get(0));
        result.put("files", files(id));
        return result;
    }

    public List<Map<String, Object>> files(long id) {
        ensureExists(id);
        return jdbc.queryForList("SELECT path,source_url,content_type,size_bytes,content_digest,is_binary FROM discovered_skill_file WHERE discovered_skill_id=? ORDER BY path", id);
    }

    public Map<String, Object> file(long id, String path) {
        ensureExists(id);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,path,source_url,content,content_type,size_bytes,is_binary FROM discovered_skill_file WHERE discovered_skill_id=? AND path=?", id, path);
        if (rows.isEmpty()) throw new IllegalArgumentException("发现技能文件不存在");
        return rows.get(0);
    }

    public void cacheFileContent(long fileId, byte[] content, String digest, long sizeBytes) {
        jdbc.update("UPDATE discovered_skill_file SET content=?,content_digest=?,size_bytes=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", content, digest, sizeBytes, fileId);
    }

    public void incrementDownloadCount(long id) {
        if (jdbc.update("UPDATE discovered_skill SET discovery_download_count=discovery_download_count+1 WHERE id=? AND visibility_status='ACTIVE'", id) != 1) {
            throw new IllegalArgumentException("发现技能不存在或已不可用");
        }
    }

    @Transactional
    public long upsert(DiscoveredSkillRecord value, List<DiscoveredFileRecord> files) {
        jdbc.update(
                "INSERT INTO discovered_skill(source_type,source_owner,source_repository,source_branch,source_path,source_url,install_url,package_type,name,description,category,supported_agents,github_stars,github_forks,external_install_count,trend_score,quality_score,trust_level,license,source_updated_at,last_synced_at,sync_status,visibility_status,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,'SYNCED','ACTIVE',CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (source_type,source_owner,source_repository,source_path) DO UPDATE SET source_branch=EXCLUDED.source_branch,source_url=EXCLUDED.source_url,install_url=EXCLUDED.install_url,package_type=EXCLUDED.package_type,name=EXCLUDED.name,description=EXCLUDED.description,category=EXCLUDED.category,supported_agents=EXCLUDED.supported_agents,github_stars=EXCLUDED.github_stars,github_forks=EXCLUDED.github_forks,external_install_count=EXCLUDED.external_install_count,trend_score=EXCLUDED.trend_score,quality_score=EXCLUDED.quality_score,trust_level=EXCLUDED.trust_level,license=EXCLUDED.license,source_updated_at=EXCLUDED.source_updated_at,last_synced_at=CURRENT_TIMESTAMP,sync_status='SYNCED',visibility_status='ACTIVE',updated_at=CURRENT_TIMESTAMP",
                value.getSourceType(), value.getSourceOwner(), value.getSourceRepository(), value.getSourceBranch(), value.getSourcePath(), value.getSourceUrl(), value.getInstallUrl(), value.getPackageType(), value.getName(), value.getDescription(), value.getCategory(), value.getSupportedAgents(), value.getGithubStars(), value.getGithubForks(), value.getExternalInstallCount(), value.getTrendScore(), value.getQualityScore(), value.getTrustLevel(), value.getLicense(), value.getSourceUpdatedAt());
        long id = jdbc.queryForObject("SELECT id FROM discovered_skill WHERE source_type=? AND source_owner=? AND source_repository=? AND source_path=?", Long.class, value.getSourceType(), value.getSourceOwner(), value.getSourceRepository(), value.getSourcePath());
        // File contents are loaded lazily from GitHub. Remove the previous
        // snapshot before replacing metadata so deleted/renamed remote files
        // do not remain visible and stale cached bytes are never reused.
        jdbc.update("DELETE FROM discovered_skill_file WHERE discovered_skill_id=?", id);
        if (!files.isEmpty()) {
            List<Object[]> fileArgs = new ArrayList<Object[]>(files.size());
            for (DiscoveredFileRecord file : files) {
                fileArgs.add(new Object[] {
                        id,
                        file.getPath(),
                        file.getSourceUrl(),
                        file.getContentType(),
                        file.getSizeBytes(),
                        file.isBinary()
                });
            }
            jdbc.batchUpdate(
                    "INSERT INTO discovered_skill_file(discovered_skill_id,path,source_url,content_type,size_bytes,is_binary,updated_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    fileArgs);
        }
        return id;
    }

    public void markUnavailable(String sourceOwner, String sourceRepository) {
        jdbc.update("UPDATE discovered_skill SET visibility_status='UNAVAILABLE',sync_status='ERROR',updated_at=CURRENT_TIMESTAMP WHERE source_owner=? AND source_repository=?", sourceOwner, sourceRepository);
    }

    private void ensureExists(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM discovered_skill WHERE id=? AND visibility_status='ACTIVE'", Integer.class, id);
        if (count == null || count == 0) throw new IllegalArgumentException("发现技能不存在或已不可用");
    }

    private String sortClause(String sort) {
        if ("stars".equalsIgnoreCase(sort)) return "github_stars DESC, id DESC";
        if ("updated".equalsIgnoreCase(sort)) return "source_updated_at DESC NULLS LAST, id DESC";
        if ("popular".equalsIgnoreCase(sort)) return "external_install_count DESC, github_stars DESC, id DESC";
        return "trend_score DESC, github_stars DESC, source_updated_at DESC NULLS LAST, id DESC";
    }
}
