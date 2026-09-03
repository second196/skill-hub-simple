package com.km.skillhub.asset.service.impl;

import com.km.skillhub.asset.model.dto.SkillPackageImportCommand;
import com.km.skillhub.asset.model.entity.SkillArtifactEntity;
import com.km.skillhub.asset.model.entity.SkillAssetEntity;
import com.km.skillhub.asset.model.entity.SkillImportAttemptEntity;
import com.km.skillhub.asset.model.vo.ImportAttemptVO;
import com.km.skillhub.asset.service.SkillPackageImportService;
import com.km.skillhub.content.model.entity.SkillVersionFileEntity;
import com.km.skillhub.integration.artifact.ArtifactStore;
import com.km.skillhub.mapper.asset.SkillArtifactMapper;
import com.km.skillhub.mapper.asset.SkillAssetMapper;
import com.km.skillhub.mapper.asset.SkillImportAttemptMapper;
import com.km.skillhub.mapper.content.SkillVersionFileMapper;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SkillPackageImportServiceImpl implements SkillPackageImportService {

    private final SkillImportAttemptMapper attemptMapper;
    private final SkillAssetMapper assetMapper;
    private final SkillArtifactMapper artifactMapper;
    private final SkillVersionMapper versionMapper;
    private final SkillVersionFileMapper fileMapper;
    private final ArtifactStore artifactStore;
    private final long maxPackageBytes;

    public SkillPackageImportServiceImpl(SkillImportAttemptMapper attemptMapper,
                                         SkillAssetMapper assetMapper,
                                         SkillArtifactMapper artifactMapper,
                                         SkillVersionMapper versionMapper,
                                         SkillVersionFileMapper fileMapper,
                                         ArtifactStore artifactStore,
                                         @Value("${skillhub.artifact.max-package-bytes:10485760}") long maxPackageBytes) {
        this.attemptMapper = attemptMapper;
        this.assetMapper = assetMapper;
        this.artifactMapper = artifactMapper;
        this.versionMapper = versionMapper;
        this.fileMapper = fileMapper;
        this.artifactStore = artifactStore;
        this.maxPackageBytes = maxPackageBytes;
    }

    @Override
    @Transactional
    public ImportAttemptVO importPackage(SkillPackageImportCommand command, String actor) {
        validateCommand(command);
        SkillImportAttemptEntity existing = attemptMapper.findByRequestId(command.getRequestId());
        if (existing != null) {
            return toVO(existing);
        }

        SkillImportAttemptEntity attempt = new SkillImportAttemptEntity();
        attempt.setRequestId(command.getRequestId());
        attempt.setSourceType("PACKAGE");
        attempt.setSourceLocator(command.getSourceLocator());
        attempt.setStatus("STARTED");
        attempt.setCreatedBy(actor);
        attemptMapper.insertAttempt(attempt);

        try {
            List<PackageFile> files = readPackage(command.getPackageBytes());
            String digest = sha256(command.getPackageBytes());
            ArtifactStore.ArtifactLocation location = artifactStore.store(command.getPackageBytes(), digest,
                    "application/zip");
            SkillAssetEntity asset = assetMapper.findByAssetKey(command.getAssetKey());
            if (asset == null) {
                asset = new SkillAssetEntity();
                asset.setAssetKey(command.getAssetKey());
                asset.setName(command.getName());
                asset.setDescription(command.getDescription());
                asset.setOwnerScopeId(command.getOwnerScopeId());
                asset.setStatus("ACTIVE");
                asset.setCreatedBy(actor);
                asset.setUpdatedBy(actor);
                assetMapper.insertAsset(asset);
            }

            SkillArtifactEntity artifact = new SkillArtifactEntity();
            artifact.setAssetId(asset.getId());
            artifact.setArtifactUri(location.getUri());
            artifact.setArtifactDigest(digest);
            artifact.setMediaType(location.getMediaType());
            artifact.setSizeBytes(location.getSizeBytes());
            artifact.setSourceSnapshotUri(command.getSourceLocator());
            artifact.setEncryptionStatus("UNKNOWN");
            artifact.setCreatedBy(actor);
            artifactMapper.insertArtifact(artifact);

            SkillVersionEntity version = new SkillVersionEntity();
            version.setAssetId(asset.getId());
            version.setArtifactId(artifact.getId());
            version.setVersionLabel(command.getVersionLabel());
            version.setVersionDigest(digest);
            version.setSourceType("PACKAGE");
            version.setSourceLocator(command.getSourceLocator());
            version.setSourceSnapshotUri(command.getSourceLocator());
            version.setMetadataStatus("COMPLETE");
            version.setLifecycleState("CANDIDATE");
            version.setCreatedBy(actor);
            versionMapper.insertVersion(version);

            for (PackageFile file : files) {
                SkillVersionFileEntity manifest = new SkillVersionFileEntity();
                manifest.setVersionId(version.getId());
                manifest.setPath(file.path);
                manifest.setRequired("SKILL.md".equals(file.path));
                manifest.setReadStatus("READABLE");
                manifest.setContentDigest(file.digest);
                fileMapper.insertFile(manifest);
            }

            attempt.setAssetId(asset.getId());
            attempt.setArtifactDigest(digest);
            attempt.setStatus("SUCCEEDED");
            attemptMapper.updateResult(attempt);
            return toVO(attempt, digest);
        } catch (RuntimeException exception) {
            attempt.setStatus("FAILED");
            attempt.setFailureStage(failureStage(exception));
            attempt.setFailureCode(exception instanceof IllegalArgumentException ? "PACKAGE_INVALID" : "IMPORT_FAILED");
            attempt.setFailureReason(safeMessage(exception));
            attemptMapper.updateResult(attempt);
            return toVO(attempt);
        }
    }

    private List<PackageFile> readPackage(byte[] bytes) {
        if (bytes.length > maxPackageBytes) {
            throw new IllegalArgumentException("Skill 包超过大小限制");
        }
        List<PackageFile> files = new ArrayList<PackageFile>();
        boolean hasSkillDescription = false;
        try {
            ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes));
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String path = normalizePath(entry.getName());
                if (entry.isDirectory()) {
                    continue;
                }
                if ("SKILL.md".equals(path)) {
                    hasSkillDescription = true;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                byte[] content = output.toByteArray();
                files.add(new PackageFile(path, sha256(content)));
            }
            input.close();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Skill 包无法读取", exception);
        }
        if (!hasSkillDescription || files.isEmpty()) {
            throw new IllegalArgumentException("Skill 包缺少根目录 SKILL.md 或文件内容");
        }
        return files;
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty() || rawPath.startsWith("/")
                || rawPath.startsWith("\\") || rawPath.contains("..") || rawPath.contains("\\")) {
            throw new IllegalArgumentException("Skill 包包含非法文件路径");
        }
        return rawPath;
    }

    private void validateCommand(SkillPackageImportCommand command) {
        if (command == null || blank(command.getRequestId()) || blank(command.getAssetKey())
                || blank(command.getName()) || blank(command.getDescription()) || command.getOwnerScopeId() == null
                || command.getOwnerScopeId() <= 0 || blank(command.getVersionLabel())
                || blank(command.getSourceLocator()) || command.getPackageBytes() == null
                || command.getPackageBytes().length == 0 || blank(command.getFilename())) {
            throw new IllegalArgumentException("Skill 包导入信息不完整");
        }
    }

    private String failureStage(RuntimeException exception) {
        return exception instanceof IllegalArgumentException ? "PACKAGE_VALIDATION" : "PERSISTENCE";
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content);
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 2048));
    }

    private ImportAttemptVO toVO(SkillImportAttemptEntity attempt) {
        return toVO(attempt, attempt.getArtifactDigest());
    }

    private ImportAttemptVO toVO(SkillImportAttemptEntity attempt, String digest) {
        return new ImportAttemptVO(attempt.getRequestId(), attempt.getStatus(), attempt.getAssetId(), digest,
                attempt.getFailureStage(), attempt.getFailureCode(), attempt.getFailureReason());
    }

    private static class PackageFile {
        private final String path;
        private final String digest;

        private PackageFile(String path, String digest) {
            this.path = path;
            this.digest = digest;
        }
    }
}
