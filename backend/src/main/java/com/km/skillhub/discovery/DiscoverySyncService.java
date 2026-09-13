package com.km.skillhub.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DiscoverySyncService {
    private static final String GITHUB_API = "https://api.github.com";
    private static final Logger logger = LoggerFactory.getLogger(DiscoverySyncService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final DiscoveredSkillRepository repository;
    private final String configuredRepositories;
    private final String searchQuery;
    private final int maxRepositories;
    private final boolean syncEnabled;

    public DiscoverySyncService(RestTemplateBuilder builder,
                                ObjectMapper objectMapper,
                                DiscoveredSkillRepository repository,
                                @Value("${skillhub.discovery.github.repositories:}") String configuredRepositories,
                                @Value("${skillhub.discovery.github.search-query:topic:agent-skills}") String searchQuery,
                                @Value("${skillhub.discovery.github.max-repositories:12}") int maxRepositories,
                                @Value("${skillhub.discovery.sync.enabled:true}") boolean syncEnabled) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.configuredRepositories = configuredRepositories == null ? "" : configuredRepositories;
        this.searchQuery = searchQuery;
        this.maxRepositories = Math.max(1, Math.min(50, maxRepositories));
        this.syncEnabled = syncEnabled;
    }

    /**
     * Refresh the discovery catalog in the background. The initial delay and
     * interval are configurable in application.yml; no user request is
     * required to keep GitHub metadata current.
     */
    @Scheduled(
            initialDelayString = "${skillhub.discovery.sync.initial-delay-ms:5000}",
            fixedDelayString = "${skillhub.discovery.sync.interval-ms:21600000}"
    )
    public void scheduledSync() {
        if (!syncEnabled) return;
        try {
            SyncSummary summary = syncConfiguredSources();
            if (summary.getErrors().isEmpty()) {
                logger.info("Discovery sync completed: {} skills synchronized", summary.getItemsSynced());
            } else {
                logger.warn("Discovery sync completed with {} skills synchronized and {} errors: {}",
                        summary.getItemsSynced(), summary.getErrors().size(), summary.getErrors());
            }
        } catch (RuntimeException exception) {
            logger.warn("Discovery scheduled sync failed", exception);
        }
    }

    public synchronized SyncSummary syncConfiguredSources() {
        List<String> repositories = configuredRepositories();
        if (repositories.isEmpty()) repositories = searchRepositories();
        int synced = 0;
        List<String> errors = new ArrayList<String>();
        for (String coordinate : repositories) {
            try {
                synced += syncRepository(coordinate);
            } catch (RuntimeException exception) {
                errors.add(coordinate + ": " + exception.getMessage());
                String[] parts = coordinate.split("/", 2);
                if (parts.length == 2) repository.markUnavailable(parts[0], parts[1]);
            }
        }
        return new SyncSummary(synced, errors);
    }

    public byte[] loadFileContent(long id, String path) {
        Map<String, Object> file = repository.file(id, path);
        byte[] cached = (byte[]) file.get("content");
        if (cached != null) return cached;
        String sourceUrl = String.valueOf(file.get("source_url"));
        assertAllowedSource(sourceUrl);
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(sourceUrl, HttpMethod.GET, requestEntity(), byte[].class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalArgumentException("外部文件暂时不可用");
            }
            byte[] content = response.getBody();
            String digest = sha256(content);
            repository.cacheFileContent(((Number) file.get("id")).longValue(), content, digest, content.length);
            return content;
        } catch (RestClientException exception) {
            throw new IllegalArgumentException("外部文件暂时不可用", exception);
        }
    }

    public void assertAllowedSource(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"raw.githubusercontent.com".equalsIgnoreCase(host)
                    && !"codeload.github.com".equalsIgnoreCase(host)
                    && !"github.com".equalsIgnoreCase(host)) {
                throw new IllegalArgumentException("发现技能来源不受支持");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("发现技能来源必须使用 HTTPS");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("发现技能来源地址无效", exception);
        }
    }

    private int syncRepository(String coordinate) {
        String[] parts = coordinate.split("/", 2);
        if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            throw new IllegalArgumentException("GitHub 仓库格式应为 owner/repository");
        }
        String owner = parts[0].trim();
        String repo = parts[1].trim();
        JsonNode metadata = getJson(GITHUB_API + "/repos/" + owner + "/" + repo);
        String branch = text(metadata, "default_branch", "main");
        JsonNode tree = getJson(GITHUB_API + "/repos/" + owner + "/" + repo + "/git/trees/" + branch + "?recursive=1");
        List<String> skillPaths = new ArrayList<String>();
        Map<String, JsonNode> blobs = new HashMap<String, JsonNode>();
        JsonNode treeItems = tree.get("tree");
        if (treeItems != null && treeItems.isArray()) {
            for (JsonNode item : treeItems) {
                if (!"blob".equals(text(item, "type", ""))) continue;
                String path = text(item, "path", "");
                blobs.put(path, item);
                if (path.equalsIgnoreCase("SKILL.md") || path.toLowerCase(Locale.ROOT).endsWith("/skill.md")) skillPaths.add(path);
            }
        }
        if (skillPaths.isEmpty()) throw new IllegalArgumentException("仓库中没有 SKILL.md");
        Collections.sort(skillPaths);
        int count = 0;
        if (skillPaths.size() > 1) {
            String commonRoot = commonRoot(skillPaths);
            List<DiscoveredFileRecord> compositeFiles = filesForRoot(owner, repo, branch, commonRoot, blobs);
            DiscoveredSkillRecord composite = record(metadata, owner, repo, branch, commonRoot, "COMPOSITE",
                    text(metadata, "name", repo) + " 技能集合",
                    text(metadata, "description", "包含多个可独立使用的 Agent Skill。") + "，包含 " + skillPaths.size() + " 个子技能。");
            repository.upsert(composite, compositeFiles);
            count++;
        }
        for (String skillPath : skillPaths) {
            String root = skillPath.equalsIgnoreCase("SKILL.md") ? "" : skillPath.substring(0, skillPath.length() - "SKILL.md".length());
            root = trimSlashes(root);
            // Do not fetch every SKILL.md during synchronization. Large
            // repositories can contain hundreds of skills and raw GitHub
            // requests are intentionally deferred until a user opens a
            // detail page. The path and repository metadata provide stable
            // fallbacks for the discovery index.
            Map<String, String> frontmatter = Collections.emptyMap();
            String name = firstNonBlank(frontmatter.get("name"), lastSegment(root), repo);
            String description = firstNonBlank(frontmatter.get("description"), text(metadata, "description", ""), "公开 Agent Skill。");
            List<DiscoveredFileRecord> skillFiles = filesForRoot(owner, repo, branch, root, blobs);
            DiscoveredSkillRecord record = record(metadata, owner, repo, branch, root, "SINGLE", name, description);
            repository.upsert(record, skillFiles);
            count++;
        }
        return count;
    }

    private DiscoveredSkillRecord record(JsonNode metadata, String owner, String repo, String branch, String root,
                                         String packageType, String name, String description) {
        DiscoveredSkillRecord value = new DiscoveredSkillRecord();
        value.setSourceType("GITHUB");
        value.setSourceOwner(owner);
        value.setSourceRepository(repo);
        value.setSourceBranch(branch);
        value.setSourcePath(root);
        value.setSourceUrl("https://github.com/" + owner + "/" + repo);
        value.setInstallUrl("https://github.com/" + owner + "/" + repo + (root.isEmpty() ? "" : "/tree/" + branch + "/" + root));
        value.setPackageType(packageType);
        value.setName(trim(name, 256));
        value.setDescription(trim(description, 4096));
        value.setCategory(inferCategory(name + " " + description + " " + text(metadata, "topics", "")));
        value.setSupportedAgents("Codex, Claude Code, Cursor");
        value.setGithubStars(longValue(metadata, "stargazers_count"));
        value.setGithubForks(longValue(metadata, "forks_count"));
        value.setExternalInstallCount(0);
        value.setTrendScore(value.getGithubStars());
        value.setQualityScore(Math.min(100, value.getGithubStars() > 0 ? 60 + Math.log10(value.getGithubStars() + 1) * 10 : 30));
        value.setTrustLevel("COMMUNITY");
        JsonNode license = metadata.get("license");
        value.setLicense(license == null || license.isNull() ? null : text(license, "spdx_id", text(license, "name", null)));
        String updated = text(metadata, "updated_at", null);
        if (updated != null) {
            try { value.setSourceUpdatedAt(OffsetDateTime.parse(updated)); } catch (RuntimeException ignored) {}
        }
        return value;
    }

    private List<DiscoveredFileRecord> filesForRoot(String owner, String repo, String branch, String root, Map<String, JsonNode> blobs) {
        String prefix = root.isEmpty() ? "" : root + "/";
        List<DiscoveredFileRecord> result = new ArrayList<DiscoveredFileRecord>();
        for (Map.Entry<String, JsonNode> entry : blobs.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith(prefix)) continue;
            String relative = path.substring(prefix.length());
            if (relative.isEmpty()) continue;
            boolean binary = isBinary(relative);
            result.add(new DiscoveredFileRecord(relative,
                    "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch + "/" + path,
                    contentType(relative), longValue(entry.getValue(), "size"), binary));
        }
        return result;
    }

    private List<String> configuredRepositories() {
        if (configuredRepositories.trim().isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        for (String value : configuredRepositories.split("[,\\s]+")) if (!value.trim().isEmpty()) result.add(value.trim());
        return result;
    }

    private List<String> searchRepositories() {
        JsonNode response = getJson(GITHUB_API + "/search/repositories?q=" + encode(searchQuery) + "&sort=stars&order=desc&per_page=" + maxRepositories);
        List<String> result = new ArrayList<String>();
        JsonNode items = response.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                String fullName = text(item, "full_name", "");
                if (!fullName.isEmpty()) result.add(fullName);
                if (result.size() >= maxRepositories) break;
            }
        }
        return result;
    }

    private JsonNode getJson(String url) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity(), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) throw new IllegalArgumentException("GitHub 返回异常");
            return objectMapper.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalArgumentException("GitHub 数据暂时不可用", exception);
        }
    }

    private HttpEntity<Void> requestEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "SkillHub-Discovery");
        return new HttpEntity<Void>(headers);
    }

    private String inferCategory(String value) {
        String text = value.toLowerCase(Locale.ROOT);
        if (containsAny(text, "test", "qa", "testing", "测试")) return "测试";
        if (containsAny(text, "design", "ui", "ux", "frontend", "前端", "设计")) return "设计";
        if (containsAny(text, "review", "audit", "lint", "审查", "reviewer")) return "代码审查";
        if (containsAny(text, "devops", "docker", "kubernetes", "deploy", "ci/cd")) return "DevOps";
        if (containsAny(text, "data", "sql", "analytics", "数据")) return "数据分析";
        return "开发";
    }

    private Map<String, String> parseFrontmatter(String markdown) {
        Map<String, String> result = new HashMap<String, String>();
        if (markdown == null || !markdown.startsWith("---")) return result;
        int end = markdown.indexOf("\n---", 3);
        if (end < 0) return result;
        for (String line : markdown.substring(3, end).split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim().replaceAll("^[\"']|[\"']$", "");
            result.put(key, value);
        }
        return result;
    }

    private String decodeContent(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(java.util.Base64.getMimeDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private String commonRoot(List<String> paths) {
        if (paths.isEmpty()) return "";
        String[] first = paths.get(0).split("/");
        int commonSegments = Math.max(0, first.length - 1);
        for (String path : paths) {
            String[] segments = path.split("/");
            int limit = Math.min(commonSegments, Math.max(0, segments.length - 1));
            int matched = 0;
            while (matched < limit && first[matched].equals(segments[matched])) matched++;
            commonSegments = matched;
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < commonSegments; index++) {
            if (index > 0) result.append('/');
            result.append(first[index]);
        }
        return result.toString();
    }

    private boolean isBinary(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(png|jpe?g|gif|webp|ico|pdf|zip|gz|woff2?|ttf|eot|bin|xls[xm]?|doc[xm]?)$");
    }

    private String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdx")) return "text/markdown";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "text/yaml";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".py") || lower.endsWith(".java")) return "text/plain";
        return isBinary(path) ? "application/octet-stream" : "text/plain";
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private long longValue(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isNumber() ? value.longValue() : 0;
    }

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private String firstNonBlank(String first, String second, String fallback) {
        return first != null && !first.trim().isEmpty() ? first.trim()
                : second != null && !second.trim().isEmpty() ? second.trim() : fallback;
    }

    private String lastSegment(String value) {
        if (value == null || value.isEmpty()) return "";
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private String trimSlashes(String value) {
        return value == null ? "" : value.replaceAll("^/+|/+$", "");
    }

    private String trim(String value, int max) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte item : value) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static class SyncSummary {
        private final int itemsSynced;
        private final List<String> errors;

        public SyncSummary(int itemsSynced, List<String> errors) {
            this.itemsSynced = itemsSynced;
            this.errors = errors;
        }

        public int getItemsSynced() { return itemsSynced; }
        public List<String> getErrors() { return errors; }
    }
}
