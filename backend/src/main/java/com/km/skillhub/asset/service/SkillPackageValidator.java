package com.km.skillhub.asset.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.asset.model.vo.SkillPackageValidationVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SkillPackageValidator {
    private static final long ZIP_END_SIGNATURE = 0x06054b50L;
    private static final long ZIP_CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long ZIP_LOCAL_SIGNATURE = 0x04034b50L;
    private static final int ZIP64_16 = 0xffff;
    private static final long ZIP64_32 = 0xffffffffL;
    private static final int MAX_FRONTMATTER_BYTES = 64 * 1024;
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[a-zA-Z]:.*");
    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");
    private static final Set<String> SENSITIVE_FILE_NAMES;

    static {
        Set<String> names = new HashSet<String>();
        Collections.addAll(names, ".env", ".npmrc", ".pypirc", ".netrc", "credentials.json",
                "id_rsa", "id_ed25519");
        SENSITIVE_FILE_NAMES = Collections.unmodifiableSet(names);
    }

    private final long maxArchiveBytes;
    private final long maxExpandedBytes;
    private final long maxSingleFileBytes;
    private final int maxFiles;
    private final int maxPathLength;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillPackageValidator(
            @Value("${skillhub.artifact.max-package-bytes:10485760}") long maxArchiveBytes,
            @Value("${skillhub.artifact.max-expanded-bytes:104857600}") long maxExpandedBytes,
            @Value("${skillhub.artifact.max-single-file-bytes:10485760}") long maxSingleFileBytes,
            @Value("${skillhub.artifact.max-files:1000}") int maxFiles,
            @Value("${skillhub.artifact.max-path-length:255}") int maxPathLength) {
        this.maxArchiveBytes = requirePositive(maxArchiveBytes, "maxArchiveBytes");
        this.maxExpandedBytes = requirePositive(maxExpandedBytes, "maxExpandedBytes");
        this.maxSingleFileBytes = requirePositive(maxSingleFileBytes, "maxSingleFileBytes");
        this.maxFiles = requirePositive(maxFiles, "maxFiles");
        this.maxPathLength = requirePositive(maxPathLength, "maxPathLength");
    }

    public SkillPackageValidationVO validate(byte[] archive) {
        if (archive == null || archive.length == 0) {
            throw invalid("EMPTY_SKILL_PACKAGE", "Skill 包不能为空");
        }
        if (archive.length > maxArchiveBytes) {
            throw invalid("PACKAGE_ARCHIVE_TOO_LARGE", "Skill 压缩包超过大小限制");
        }
        inspectCentralDirectory(archive);
        List<PackageEntry> files = readFiles(archive);
        PackageEntry skillFile = findSkillFile(files);
        Metadata metadata = parseMetadata(skillFile.content);
        List<SkillPackageValidationVO.ManifestEntry> manifest = createManifest(files);
        String artifactDigest = sha256(archive);
        String versionDigest = sha256(canonicalVersion(metadata, manifest));
        long expandedSize = 0L;
        for (SkillPackageValidationVO.ManifestEntry entry : manifest) {
            expandedSize += entry.getSizeBytes();
        }
        return new SkillPackageValidationVO(metadata.name, metadata.description, metadata.version,
                artifactDigest, versionDigest, expandedSize, manifest);
    }

    private void inspectCentralDirectory(byte[] archive) {
        int endOffset = findEndRecord(archive);
        if (endOffset < 0 || endOffset + 22 > archive.length) invalidArchive();
        int disk = unsignedShort(archive, endOffset + 4);
        int centralDisk = unsignedShort(archive, endOffset + 6);
        int entriesOnDisk = unsignedShort(archive, endOffset + 8);
        int entryCount = unsignedShort(archive, endOffset + 10);
        long centralSize = unsignedInt(archive, endOffset + 12);
        long centralOffset = unsignedInt(archive, endOffset + 16);
        int commentLength = unsignedShort(archive, endOffset + 20);
        if (entryCount == ZIP64_16 || centralSize == ZIP64_32 || centralOffset == ZIP64_32) {
            throw invalid("UNSUPPORTED_SKILL_ARCHIVE", "暂不支持 ZIP64 Skill 包");
        }
        if (disk != 0 || centralDisk != 0 || entriesOnDisk != entryCount) {
            throw invalid("UNSUPPORTED_SKILL_ARCHIVE", "暂不支持分卷 Skill ZIP");
        }
        if (entryCount > maxFiles * 2L) {
            throw invalid("PACKAGE_FILE_COUNT_EXCEEDED", "Skill 包文件数量超过限制");
        }
        if (endOffset + 22L + commentLength != archive.length
                || centralOffset + centralSize > endOffset) {
            invalidArchive();
        }

        Set<String> paths = new HashSet<String>();
        long offset = centralOffset;
        int fileCount = 0;
        long expandedBytes = 0L;
        for (int index = 0; index < entryCount; index++) {
            if (offset + 46 > endOffset || unsignedInt(archive, (int) offset) != ZIP_CENTRAL_SIGNATURE) {
                invalidArchive();
            }
            int flags = unsignedShort(archive, (int) offset + 8);
            if ((flags & 0x1) != 0) {
                throw invalid("UNSUPPORTED_SKILL_ARCHIVE", "不支持加密 Skill ZIP");
            }
            long uncompressedSize = unsignedInt(archive, (int) offset + 24);
            int pathLength = unsignedShort(archive, (int) offset + 28);
            int extraLength = unsignedShort(archive, (int) offset + 30);
            int entryCommentLength = unsignedShort(archive, (int) offset + 32);
            long externalAttributes = unsignedInt(archive, (int) offset + 38);
            long localOffset = unsignedInt(archive, (int) offset + 42);
            if (uncompressedSize == ZIP64_32 || localOffset == ZIP64_32) {
                throw invalid("UNSUPPORTED_SKILL_ARCHIVE", "暂不支持 ZIP64 Skill 包");
            }
            long pathStart = offset + 46;
            long pathEnd = pathStart + pathLength;
            long nextOffset = pathEnd + extraLength + entryCommentLength;
            if (pathEnd > endOffset || nextOffset > endOffset) invalidArchive();
            String rawPath = decodePath(archive, (int) pathStart, pathLength);
            boolean directory = rawPath.endsWith("/");
            String path = normalizePath(directory ? rawPath.substring(0, rawPath.length() - 1) : rawPath);
            if (!paths.add(path)) {
                throw invalid("DUPLICATE_PACKAGE_PATH", "Skill 包包含重复路径：" + path);
            }
            int unixMode = (int) ((externalAttributes >>> 16) & 0xffff);
            if ((unixMode & 0xf000) == 0xa000) {
                throw invalid("SYMLINK_NOT_ALLOWED", "Skill 包不允许符号链接：" + path);
            }
            validateLocalHeader(archive, localOffset, rawPath);
            if (!directory) {
                fileCount++;
                expandedBytes += uncompressedSize;
                checkFileLimits(fileCount, uncompressedSize, expandedBytes, path);
            }
            offset = nextOffset;
        }
        if (offset != centralOffset + centralSize) invalidArchive();
    }

    private List<PackageEntry> readFiles(byte[] archive) {
        List<PackageEntry> files = new ArrayList<PackageEntry>();
        Set<String> paths = new HashSet<String>();
        long totalBytes = 0L;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String rawPath = entry.getName();
                boolean directory = entry.isDirectory() || rawPath.endsWith("/");
                String path = normalizePath(directory ? rawPath.substring(0, rawPath.length() - 1) : rawPath);
                if (!paths.add(path)) {
                    throw invalid("DUPLICATE_PACKAGE_PATH", "Skill 包包含重复路径：" + path);
                }
                if (directory) continue;
                if (isSensitivePath(path)) {
                    throw invalid("SENSITIVE_PACKAGE_PATH", "Skill ZIP 包含版本库或凭据文件：" + path);
                }

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long fileBytes = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    fileBytes += read;
                    totalBytes += read;
                    checkFileLimits(files.size() + 1, fileBytes, totalBytes, path);
                    output.write(buffer, 0, read);
                }
                files.add(new PackageEntry(path, output.toByteArray()));
            }
        } catch (SkillPackageValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SkillPackageValidationException("INVALID_SKILL_ARCHIVE", "Skill ZIP 无法解压", exception);
        }
        if (files.isEmpty()) {
            throw invalid("EMPTY_SKILL_PACKAGE", "Skill 包不能为空");
        }
        return files;
    }

    private Metadata parseMetadata(byte[] content) {
        String markdown = decodeUtf8(content);
        String normalized = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        String[] lines = normalized.split("\\r?\\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0])) {
            throw invalid("INVALID_SKILL_FRONTMATTER", "SKILL.md 缺少 YAML frontmatter");
        }
        int closingIndex = -1;
        int frontmatterBytes = 0;
        for (int index = 1; index < lines.length; index++) {
            frontmatterBytes += lines[index].getBytes(StandardCharsets.UTF_8).length + 1;
            if (frontmatterBytes > MAX_FRONTMATTER_BYTES) {
                throw invalid("INVALID_SKILL_FRONTMATTER", "SKILL.md 的 YAML frontmatter 过大");
            }
            if ("---".equals(lines[index]) || "...".equals(lines[index])) {
                closingIndex = index;
                break;
            }
        }
        if (closingIndex < 0) {
            throw invalid("INVALID_SKILL_FRONTMATTER", "SKILL.md 的 YAML frontmatter 未闭合");
        }
        StringBuilder yamlText = new StringBuilder();
        for (int index = 1; index < closingIndex; index++) {
            if (index > 1) yamlText.append('\n');
            yamlText.append(lines[index]);
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(yamlText.toString());
        } catch (RuntimeException exception) {
            throw new SkillPackageValidationException(
                    "INVALID_SKILL_FRONTMATTER", "SKILL.md 的 YAML frontmatter 无法解析", exception);
        }
        if (!(loaded instanceof Map)) {
            throw invalid("INVALID_SKILL_METADATA", "SKILL.md 的名称、描述或版本无效");
        }
        Map<?, ?> values = (Map<?, ?>) loaded;
        String name = requiredText(values.get("name"), 100);
        String description = requiredText(values.get("description"), 2000);
        String version = requiredText(values.get("version"), 64);
        if (name == null || description == null || version == null || !SEMANTIC_VERSION.matcher(version).matches()) {
            throw invalid("INVALID_SKILL_METADATA", "SKILL.md 的名称、描述或版本无效");
        }
        return new Metadata(name, description, version);
    }

    private List<SkillPackageValidationVO.ManifestEntry> createManifest(List<PackageEntry> files) {
        Collections.sort(files, new Comparator<PackageEntry>() {
            @Override
            public int compare(PackageEntry left, PackageEntry right) {
                return compareUtf8(left.path, right.path);
            }
        });
        List<SkillPackageValidationVO.ManifestEntry> manifest =
                new ArrayList<SkillPackageValidationVO.ManifestEntry>(files.size());
        for (PackageEntry file : files) {
            manifest.add(new SkillPackageValidationVO.ManifestEntry(
                    file.path, file.content.length, sha256(file.content)));
        }
        return manifest;
    }

    private byte[] canonicalVersion(Metadata metadata, List<SkillPackageValidationVO.ManifestEntry> manifest) {
        Map<String, Object> metadataJson = new LinkedHashMap<String, Object>();
        metadataJson.put("name", metadata.name);
        metadataJson.put("description", metadata.description);
        metadataJson.put("version", metadata.version);
        List<Map<String, Object>> manifestJson = new ArrayList<Map<String, Object>>();
        for (SkillPackageValidationVO.ManifestEntry entry : manifest) {
            Map<String, Object> file = new LinkedHashMap<String, Object>();
            file.put("path", entry.getPath());
            file.put("size", entry.getSizeBytes());
            file.put("digest", entry.getContentDigest());
            manifestJson.add(file);
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("metadata", metadataJson);
        root.put("manifest", manifestJson);
        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成规范化 Skill 摘要", exception);
        }
    }

    private PackageEntry findSkillFile(List<PackageEntry> files) {
        for (PackageEntry file : files) {
            if ("SKILL.md".equals(file.path)) return file;
        }
        throw invalid("SKILL_FILE_REQUIRED", "Skill 包根目录缺少 SKILL.md");
    }

    private void validateLocalHeader(byte[] archive, long offset, String expectedPath) {
        if (offset < 0 || offset + 30 > archive.length
                || unsignedInt(archive, (int) offset) != ZIP_LOCAL_SIGNATURE) invalidArchive();
        int pathLength = unsignedShort(archive, (int) offset + 26);
        int extraLength = unsignedShort(archive, (int) offset + 28);
        long pathStart = offset + 30;
        if (pathStart + pathLength + extraLength > archive.length) invalidArchive();
        String localPath = decodePath(archive, (int) pathStart, pathLength);
        if (!expectedPath.equals(localPath)) invalidArchive();
    }

    private int findEndRecord(byte[] archive) {
        int minimum = Math.max(0, archive.length - 0xffff - 22);
        for (int offset = archive.length - 22; offset >= minimum; offset--) {
            if (unsignedInt(archive, offset) == ZIP_END_SIGNATURE) return offset;
        }
        return -1;
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.indexOf('\0') >= 0
                || rawPath.indexOf('\\') >= 0 || rawPath.startsWith("/")
                || WINDOWS_ABSOLUTE_PATH.matcher(rawPath).matches()) {
            throw invalid("UNSAFE_PACKAGE_PATH", "Skill 包包含不安全路径");
        }
        String path = Normalizer.normalize(rawPath, Normalizer.Form.NFC);
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("UNSAFE_PACKAGE_PATH", "Skill 包包含不安全路径");
            }
        }
        if (path.codePointCount(0, path.length()) > maxPathLength) {
            throw invalid("PACKAGE_PATH_TOO_LONG", "Skill 包内路径超过长度限制");
        }
        return path;
    }

    private boolean isSensitivePath(String path) {
        String[] segments = path.toLowerCase().split("/");
        for (String segment : segments) {
            if (".git".equals(segment) || ".skillhub".equals(segment)) return true;
        }
        String fileName = segments[segments.length - 1];
        return SENSITIVE_FILE_NAMES.contains(fileName) || fileName.startsWith(".env.")
                || fileName.endsWith(".pem") || fileName.endsWith(".key");
    }

    private void checkFileLimits(int fileCount, long fileBytes, long totalBytes, String path) {
        if (fileCount > maxFiles) {
            throw invalid("PACKAGE_FILE_COUNT_EXCEEDED", "Skill 包文件数量超过限制");
        }
        if (fileBytes > maxSingleFileBytes) {
            throw invalid("PACKAGE_FILE_TOO_LARGE", "Skill 包内单个文件超过大小限制：" + path);
        }
        if (totalBytes > maxExpandedBytes) {
            throw invalid("PACKAGE_EXPANDED_TOO_LARGE", "Skill 包解压后超过大小限制");
        }
    }

    private String decodePath(byte[] archive, int offset, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(archive, offset, length)).toString();
        } catch (CharacterCodingException exception) {
            throw new SkillPackageValidationException(
                    "INVALID_PACKAGE_PATH_ENCODING", "Skill 包路径必须使用 UTF-8", exception);
        }
    }

    private String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException exception) {
            throw new SkillPackageValidationException(
                    "INVALID_SKILL_ENCODING", "SKILL.md 必须使用 UTF-8", exception);
        }
    }

    private int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < length; index++) {
            int difference = (leftBytes[index] & 0xff) - (rightBytes[index] & 0xff);
            if (difference != 0) return difference;
        }
        return leftBytes.length - rightBytes.length;
    }

    private int unsignedShort(byte[] bytes, int offset) {
        requireRange(bytes, offset, 2);
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
    }

    private long unsignedInt(byte[] bytes, int offset) {
        requireRange(bytes, offset, 4);
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
    }

    private void requireRange(byte[] bytes, int offset, int length) {
        if (offset < 0 || offset + length > bytes.length) invalidArchive();
    }

    private String requiredText(Object value, int maxLength) {
        if (!(value instanceof String)) return null;
        String text = ((String) value).trim();
        return text.isEmpty() || text.length() > maxLength ? null : text;
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " 必须大于 0");
        return value;
    }

    private long requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " 必须大于 0");
        return value;
    }

    private SkillPackageValidationException invalid(String code, String message) {
        return new SkillPackageValidationException(code, message);
    }

    private void invalidArchive() {
        throw invalid("INVALID_SKILL_ARCHIVE", "Skill ZIP 目录结构无效");
    }

    private static class PackageEntry {
        private final String path;
        private final byte[] content;

        private PackageEntry(String path, byte[] content) {
            this.path = path;
            this.content = content;
        }
    }

    private static class Metadata {
        private final String name;
        private final String description;
        private final String version;

        private Metadata(String name, String description, String version) {
            this.name = name;
            this.description = description;
            this.version = version;
        }
    }
}
