package com.km.skillhub.skill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class SkillPackageParser {
    private static final Pattern VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$");
    private final long maxPackageBytes;
    private final long maxExpandedBytes;
    private final int maxFiles;

    public SkillPackageParser(@Value("${skillhub.upload.max-package-bytes:10485760}") long maxPackageBytes,
                              @Value("${skillhub.upload.max-expanded-bytes:104857600}") long maxExpandedBytes,
                              @Value("${skillhub.upload.max-files:1000}") int maxFiles) {
        this.maxPackageBytes = maxPackageBytes;
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxFiles = maxFiles;
    }

    public SkillPackage parse(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("上传文件不能为空");
        if (bytes.length > maxPackageBytes) throw new IllegalArgumentException("上传文件超过大小限制");
        List<SkillPackage.FileEntry> files = isMarkdown(filename) ? singleFile(bytes) : normalizePackageRoot(unzip(filename, bytes));
        if (!hasRootSkill(files)) files = addGeneratedRootSkill(files, filename);
        SkillPackage.FileEntry skillFile = null;
        for (SkillPackage.FileEntry file : files) if ("SKILL.md".equals(file.getPath())) skillFile = file;
        if (skillFile == null) throw new IllegalArgumentException("Skill 根目录必须包含 SKILL.md");
        Map<?, ?> metadata = parseFrontmatter(skillFile.getContent());
        String name = text(metadata.get("name"), "name", 128);
        String description = text(metadata.get("description"), "description", 2048);
        Object rawVersion = metadata.get("version");
        String version = rawVersion == null ? "0.0.0" : text(rawVersion, "version", 64);
        if (!VERSION.matcher(version).matches()) throw new IllegalArgumentException("version 必须使用语义化版本号");
        Collections.sort(files, Comparator.comparing(SkillPackage.FileEntry::getPath));
        StringBuilder canonical = new StringBuilder(name).append('\n').append(version).append('\n');
        for (SkillPackage.FileEntry file : files) canonical.append(file.getPath()).append(':').append(file.getDigest()).append('\n');
        return new SkillPackage(name, description, version,
                sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)), files);
    }

    private List<SkillPackage.FileEntry> singleFile(byte[] bytes) {
        List<SkillPackage.FileEntry> result = new ArrayList<SkillPackage.FileEntry>();
        result.add(entry("SKILL.md", bytes));
        return result;
    }

    private List<SkillPackage.FileEntry> unzip(String filename, byte[] bytes) {
        if (filename == null || !filename.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("只支持 ZIP 或 SKILL.md 文件");
        }
        List<SkillPackage.FileEntry> result = new ArrayList<SkillPackage.FileEntry>();
        Set<String> paths = new HashSet<String>();
        long expanded = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry zipEntry;
            while ((zipEntry = input.getNextEntry()) != null) {
                if (zipEntry.isDirectory()) continue;
                String path = safePath(zipEntry.getName());
                if (!paths.add(path)) throw new IllegalArgumentException("ZIP 包含重复路径：" + path);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > maxExpandedBytes) throw new IllegalArgumentException("ZIP 解压后超过大小限制");
                    output.write(buffer, 0, read);
                }
                result.add(entry(path, output.toByteArray()));
                if (result.size() > maxFiles) throw new IllegalArgumentException("ZIP 文件数量超过限制");
            }
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("ZIP 文件无法读取", exception);
        }
        return result;
    }

    private List<SkillPackage.FileEntry> normalizePackageRoot(List<SkillPackage.FileEntry> files) {
        for (SkillPackage.FileEntry file : files) if ("SKILL.md".equals(file.getPath())) return files;
        SkillPackage.FileEntry nestedSkill = null;
        for (SkillPackage.FileEntry file : files) {
            if (file.getPath().endsWith("/SKILL.md")) {
                if (nestedSkill != null) return files;
                nestedSkill = file;
            }
        }
        if (nestedSkill == null) return files;
        String prefix = nestedSkill.getPath().substring(0, nestedSkill.getPath().length() - "SKILL.md".length());
        for (SkillPackage.FileEntry file : files) if (!file.getPath().startsWith(prefix)) return files;
        List<SkillPackage.FileEntry> normalized = new ArrayList<SkillPackage.FileEntry>(files.size());
        for (SkillPackage.FileEntry file : files) {
            normalized.add(entry(file.getPath().substring(prefix.length()), file.getContent()));
        }
        return normalized;
    }

    private boolean hasRootSkill(List<SkillPackage.FileEntry> files) {
        for (SkillPackage.FileEntry file : files) if ("SKILL.md".equals(file.getPath())) return true;
        return false;
    }

    private List<SkillPackage.FileEntry> addGeneratedRootSkill(List<SkillPackage.FileEntry> files, String filename) {
        int nestedSkillCount = 0;
        for (SkillPackage.FileEntry file : files) if (file.getPath().endsWith("/SKILL.md")) nestedSkillCount++;
        if (nestedSkillCount == 0) throw new IllegalArgumentException("Skill 根目录必须包含 SKILL.md");

        Map<String, String> packageMetadata = packageMetadata(files);
        String fallbackName = baseName(filename);
        String name = cleanMetadata(
                packageMetadata.get("name"),
                firstNonBlank(readmeTitle(files), fallbackName, "composite-skill"),
                100);
        String description = cleanMetadata(
                packageMetadata.get("description"),
                firstNonBlank(readmeDescription(files), "复合技能包，包含 " + nestedSkillCount + " 个子技能。"),
                2048);
        String generated = "---\n"
                + "name: " + yamlQuote(name) + "\n"
                + "description: " + yamlQuote(description) + "\n"
                + "version: 0.0.0\n"
                + "---\n\n"
                + "# " + name + "\n\n"
                + "这是一个复合技能包，包含若干可独立使用的子技能。子技能及其资源保留在原始目录结构中。\n";
        List<SkillPackage.FileEntry> result = new ArrayList<SkillPackage.FileEntry>(files.size() + 1);
        result.add(entry("SKILL.md", generated.getBytes(StandardCharsets.UTF_8)));
        result.addAll(files);
        return result;
    }

    private Map<String, String> packageMetadata(List<SkillPackage.FileEntry> files) {
        Map<String, String> result = new HashMap<String, String>();
        String[] candidates = new String[] {"package.json", ".codex-plugin/plugin.json"};
        for (String path : candidates) {
            String content = textFile(files, path);
            if (content == null) continue;
            try {
                Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
                if (!(value instanceof Map)) continue;
                Map<?, ?> map = (Map<?, ?>) value;
                if (map.get("name") instanceof String) result.put("name", ((String) map.get("name")).trim());
                if (map.get("description") instanceof String) result.put("description", ((String) map.get("description")).trim());
                if (!result.isEmpty()) return result;
            } catch (RuntimeException ignored) {
                // Invalid optional metadata should not prevent a valid composite package from uploading.
            }
        }
        return result;
    }

    private String readmeTitle(List<SkillPackage.FileEntry> files) {
        String readme = textFile(files, "README.md");
        if (readme == null) return null;
        Matcher matcher = Pattern.compile("(?m)^#\\s+(.+?)\\s*$").matcher(readme);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String readmeDescription(List<SkillPackage.FileEntry> files) {
        String readme = textFile(files, "README.md");
        if (readme == null) return null;
        String withoutTitle = readme.replaceFirst("(?m)^#\\s+.+?\\s*$", "");
        String[] paragraphs = withoutTitle.split("\\r?\\n\\s*\\r?\\n");
        for (String paragraph : paragraphs) {
            String cleaned = paragraph.replaceAll("(?m)^\\s*[-*>`#].*$", "")
                    .replaceAll("\\s+", " ").trim();
            if (!cleaned.isEmpty()) return cleaned;
        }
        return null;
    }

    private String textFile(List<SkillPackage.FileEntry> files, String path) {
        for (SkillPackage.FileEntry file : files) {
            if (!path.equalsIgnoreCase(file.getPath())) continue;
            try {
                return new String(file.getContent(), StandardCharsets.UTF_8);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private String baseName(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "composite-skill";
        String value = filename.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        if (value.toLowerCase().endsWith(".zip")) value = value.substring(0, value.length() - 4);
        return value.trim().isEmpty() ? "composite-skill" : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "composite-skill";
    }

    private String cleanMetadata(String value, String fallback, int max) {
        String candidate = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (candidate.isEmpty()) candidate = fallback;
        if (candidate.length() > max) candidate = candidate.substring(0, max).trim();
        return candidate.isEmpty() ? fallback : candidate;
    }

    private String yamlQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private Map<?, ?> parseFrontmatter(byte[] bytes) {
        String markdown = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!markdown.startsWith("---\n")) throw new IllegalArgumentException("SKILL.md 缺少 YAML frontmatter");
        int end = markdown.indexOf("\n---", 4);
        if (end < 0) end = markdown.indexOf("\n...", 4);
        if (end < 0) throw new IllegalArgumentException("SKILL.md 的 YAML frontmatter 未闭合");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        Object value = new Yaml(new SafeConstructor(options)).load(markdown.substring(4, end));
        if (!(value instanceof Map)) throw new IllegalArgumentException("SKILL.md 元数据无效");
        return (Map<?, ?>) value;
    }

    private SkillPackage.FileEntry entry(String path, byte[] content) {
        String type = path.toLowerCase().endsWith(".md") ? "text/markdown;charset=UTF-8"
                : path.toLowerCase().matches(".*\\.(txt|json|ya?ml|js|ts|py|java|css|html)$")
                ? "text/plain;charset=UTF-8" : "application/octet-stream";
        return new SkillPackage.FileEntry(path, content, type, sha256(content));
    }

    private String safePath(String raw) {
        String path = raw.replace('\\', '/');
        if (path.startsWith("/") || path.contains("../") || path.equals("..") || path.length() > 512) {
            throw new IllegalArgumentException("ZIP 包含不安全路径");
        }
        return path;
    }

    private boolean isMarkdown(String filename) {
        return filename != null && (filename.equalsIgnoreCase("SKILL.md") || filename.toLowerCase().endsWith(".md"));
    }

    private String text(Object value, String field, int max) {
        String result = value instanceof String ? ((String) value).trim() : "";
        if (result.isEmpty() || result.length() > max) throw new IllegalArgumentException("SKILL.md 的 " + field + " 无效");
        return result;
    }

    public static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder output = new StringBuilder();
            for (byte item : digest) output.append(String.format("%02x", item));
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算内容摘要", exception);
        }
    }
}
