package com.km.skillhub.asset.service.impl;

import com.km.skillhub.asset.model.dto.SkillPackageImportCommand;
import com.km.skillhub.asset.model.entity.SkillArtifactEntity;
import com.km.skillhub.asset.model.entity.SkillAssetEntity;
import com.km.skillhub.asset.model.entity.SkillImportAttemptEntity;
import com.km.skillhub.asset.model.vo.ImportAttemptVO;
import com.km.skillhub.asset.model.vo.SkillPackageValidationVO;
import com.km.skillhub.asset.service.SkillPackageConflictException;
import com.km.skillhub.asset.service.SkillPackageImportService;
import com.km.skillhub.asset.service.SkillPackageValidationException;
import com.km.skillhub.asset.service.SkillPackageValidator;
import com.km.skillhub.content.model.entity.SkillVersionFileEntity;
import com.km.skillhub.integration.artifact.ArtifactStore;
import com.km.skillhub.mapper.asset.SkillArtifactMapper;
import com.km.skillhub.mapper.asset.SkillAssetMapper;
import com.km.skillhub.mapper.asset.SkillImportAttemptMapper;
import com.km.skillhub.mapper.content.SkillVersionFileMapper;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class SkillPackageImportServiceImpl implements SkillPackageImportService {

    private final SkillImportAttemptMapper attemptMapper;
    private final SkillAssetMapper assetMapper;
    private final SkillArtifactMapper artifactMapper;
    private final SkillVersionMapper versionMapper;
    private final SkillVersionFileMapper fileMapper;
    private final ArtifactStore artifactStore;
    private final SkillPackageValidator packageValidator;
    private final TransactionOperations transactionOperations;

    @Autowired
    public SkillPackageImportServiceImpl(SkillImportAttemptMapper attemptMapper,
                                         SkillAssetMapper assetMapper,
                                         SkillArtifactMapper artifactMapper,
                                         SkillVersionMapper versionMapper,
                                         SkillVersionFileMapper fileMapper,
                                         ArtifactStore artifactStore,
                                         SkillPackageValidator packageValidator,
                                         PlatformTransactionManager transactionManager) {
        this(attemptMapper, assetMapper, artifactMapper, versionMapper, fileMapper, artifactStore,
                packageValidator, new TransactionTemplate(transactionManager));
    }

    public SkillPackageImportServiceImpl(SkillImportAttemptMapper attemptMapper,
                                         SkillAssetMapper assetMapper,
                                         SkillArtifactMapper artifactMapper,
                                         SkillVersionMapper versionMapper,
                                         SkillVersionFileMapper fileMapper,
                                         ArtifactStore artifactStore,
                                         SkillPackageValidator packageValidator,
                                         TransactionOperations transactionOperations) {
        this.attemptMapper = attemptMapper;
        this.assetMapper = assetMapper;
        this.artifactMapper = artifactMapper;
        this.versionMapper = versionMapper;
        this.fileMapper = fileMapper;
        this.artifactStore = artifactStore;
        this.packageValidator = packageValidator;
        this.transactionOperations = transactionOperations;
    }

    @Override
    public SkillPackageValidationVO validatePackage(byte[] packageBytes) {
        return packageValidator.validate(packageBytes);
    }

    @Override
    public ImportAttemptVO importPackage(SkillPackageImportCommand command, String actor) {
        validateCommand(command, actor);
        String requestDigest = sha256(command.getPackageBytes());
        SkillImportAttemptEntity existing = attemptMapper.findByRequestId(command.getRequestId());
        if (existing != null) {
            return resolveExistingRequest(existing, requestDigest);
        }

        SkillImportAttemptEntity attempt = startedAttempt(command, actor, requestDigest);
        Boolean inserted = transactionOperations.execute(status -> attemptMapper.insertAttempt(attempt) > 0);
        if (!Boolean.TRUE.equals(inserted)) {
            SkillImportAttemptEntity concurrent = attemptMapper.findByRequestId(command.getRequestId());
            if (concurrent == null) {
                throw new IllegalStateException("并发导入记录不可见");
            }
            return resolveExistingRequest(concurrent, requestDigest);
        }

        SkillPackageValidationVO validation = null;
        try {
            validation = packageValidator.validate(command.getPackageBytes());
            verifyClientDigest(command.getClientArtifactDigest(), validation.getArtifactDigest());
            ResolvedMetadata metadata = resolveMetadata(command, validation);
            SkillPackageValidationVO resolvedValidation = validation;
            return transactionOperations.execute(status -> importValidatedPackage(
                    command, actor, attempt, metadata, resolvedValidation));
        } catch (SkillPackageConflictException exception) {
            recordFailure(attempt, "VERSION_CONFLICT", exception.getCode(), exception, validation);
            throw exception;
        } catch (SkillPackageValidationException exception) {
            recordFailure(attempt, "PACKAGE_VALIDATION", exception.getCode(), exception, validation);
            return toVO(attempt, null, false, null);
        } catch (RuntimeException exception) {
            recordFailure(attempt, "PERSISTENCE", "IMPORT_FAILED", exception, validation);
            return toVO(attempt, null, false, null);
        }
    }

    private ImportAttemptVO importValidatedPackage(SkillPackageImportCommand command,
                                                    String actor,
                                                    SkillImportAttemptEntity attempt,
                                                    ResolvedMetadata metadata,
                                                    SkillPackageValidationVO validation) {
        assetMapper.lockAssetKey(metadata.assetKey);
        SkillAssetEntity asset = findOrCreateAsset(metadata, command.getOwnerScopeId(), actor);
        SkillVersionEntity existingVersion = versionMapper.findByAssetAndLabel(
                asset.getId(), metadata.versionLabel);
        if (existingVersion != null) {
            return handleExistingVersion(attempt, existingVersion, validation, asset.getId());
        }

        ArtifactStore.ArtifactLocation location = artifactStore.store(command.getPackageBytes(),
                validation.getArtifactDigest(), "application/zip");
        SkillArtifactEntity artifact = createArtifact(
                asset.getId(), command.getSourceLocator(), actor, validation, location);
        artifactMapper.insertArtifact(artifact);
        SkillVersionEntity version = createVersion(
                asset.getId(), artifact.getId(), command.getSourceLocator(), actor, metadata, validation);
        versionMapper.insertVersion(version);
        saveManifest(version.getId(), validation);

        completeAttempt(attempt, asset.getId(), validation);
        attemptMapper.updateResult(attempt);
        return toVO(attempt, "DRAFT", false, validation);
    }

    private ImportAttemptVO handleExistingVersion(SkillImportAttemptEntity attempt,
                                                   SkillVersionEntity existingVersion,
                                                   SkillPackageValidationVO validation,
                                                   Long assetId) {
        if (validation.getVersionDigest().equals(existingVersion.getVersionDigest())) {
            completeAttempt(attempt, assetId, validation);
            attemptMapper.updateResult(attempt);
            return toVO(attempt, existingVersion.getLifecycleState(), true, validation);
        }
        throw new SkillPackageConflictException(
                "VERSION_CONTENT_CONFLICT", "同一版本号已存在不同内容，请使用新的版本号");
    }

    private ImportAttemptVO resolveExistingRequest(SkillImportAttemptEntity existing, String requestDigest) {
        if (existing.getRequestDigest() != null && !requestDigest.equals(existing.getRequestDigest())) {
            throw new SkillPackageConflictException(
                    "IDEMPOTENCY_CONFLICT", "同一请求标识不能提交不同的 Skill 包");
        }
        SkillVersionEntity version = existing.getVersionDigest() == null
                ? null : versionMapper.findByDigest(existing.getVersionDigest());
        return toVO(existing, version == null ? null : version.getLifecycleState(), true, null);
    }

    private SkillImportAttemptEntity startedAttempt(SkillPackageImportCommand command,
                                                    String actor, String requestDigest) {
        SkillImportAttemptEntity attempt = new SkillImportAttemptEntity();
        attempt.setRequestId(command.getRequestId());
        attempt.setRequestDigest(requestDigest);
        attempt.setSourceType("PACKAGE");
        attempt.setSourceLocator(command.getSourceLocator());
        attempt.setStatus("STARTED");
        attempt.setCreatedBy(actor);
        return attempt;
    }

    private ResolvedMetadata resolveMetadata(SkillPackageImportCommand command,
                                             SkillPackageValidationVO validation) {
        requireCompatible("名称", command.getName(), validation.getName());
        requireCompatible("描述", command.getDescription(), validation.getDescription());
        requireCompatible("版本", command.getVersionLabel(), validation.getVersionLabel());
        String assetKey = blank(command.getAssetKey()) ? validation.getName() : command.getAssetKey().trim();
        return new ResolvedMetadata(assetKey, validation.getName(), validation.getDescription(),
                validation.getVersionLabel());
    }

    private void requireCompatible(String field, String supplied, String packageValue) {
        if (!blank(supplied) && !supplied.trim().equals(packageValue)) {
            throw new SkillPackageValidationException(
                    "PACKAGE_METADATA_CONFLICT", "表单" + field + "与 SKILL.md 不一致");
        }
    }

    private SkillAssetEntity findOrCreateAsset(ResolvedMetadata metadata, Long ownerScopeId, String actor) {
        SkillAssetEntity asset = assetMapper.findByAssetKey(metadata.assetKey);
        if (asset != null) return asset;
        asset = new SkillAssetEntity();
        asset.setAssetKey(metadata.assetKey);
        asset.setName(metadata.name);
        asset.setDescription(metadata.description);
        asset.setOwnerScopeId(ownerScopeId);
        asset.setStatus("ACTIVE");
        asset.setCreatedBy(actor);
        asset.setUpdatedBy(actor);
        assetMapper.insertAsset(asset);
        return asset;
    }

    private SkillArtifactEntity createArtifact(Long assetId, String sourceLocator, String actor,
                                               SkillPackageValidationVO validation,
                                               ArtifactStore.ArtifactLocation location) {
        SkillArtifactEntity artifact = new SkillArtifactEntity();
        artifact.setAssetId(assetId);
        artifact.setArtifactUri(location.getUri());
        artifact.setArtifactDigest(validation.getArtifactDigest());
        artifact.setMediaType(location.getMediaType());
        artifact.setSizeBytes(location.getSizeBytes());
        artifact.setSourceSnapshotUri(sourceLocator);
        artifact.setEncryptionStatus("UNKNOWN");
        artifact.setCreatedBy(actor);
        return artifact;
    }

    private SkillVersionEntity createVersion(Long assetId, Long artifactId, String sourceLocator,
                                             String actor, ResolvedMetadata metadata,
                                             SkillPackageValidationVO validation) {
        SkillVersionEntity version = new SkillVersionEntity();
        version.setAssetId(assetId);
        version.setArtifactId(artifactId);
        version.setVersionLabel(metadata.versionLabel);
        version.setVersionDigest(validation.getVersionDigest());
        version.setSourceType("PACKAGE");
        version.setSourceLocator(sourceLocator);
        version.setSourceSnapshotUri(sourceLocator);
        version.setMetadataStatus("COMPLETE");
        version.setLifecycleState("DRAFT");
        version.setCreatedBy(actor);
        return version;
    }

    private void saveManifest(Long versionId, SkillPackageValidationVO validation) {
        for (SkillPackageValidationVO.ManifestEntry file : validation.getManifest()) {
            SkillVersionFileEntity manifest = new SkillVersionFileEntity();
            manifest.setVersionId(versionId);
            manifest.setPath(file.getPath());
            manifest.setRequired("SKILL.md".equals(file.getPath()));
            manifest.setReadStatus("READABLE");
            manifest.setContentDigest(file.getContentDigest());
            fileMapper.insertFile(manifest);
        }
    }

    private void completeAttempt(SkillImportAttemptEntity attempt, Long assetId,
                                 SkillPackageValidationVO validation) {
        attempt.setAssetId(assetId);
        attempt.setRequestDigest(validation.getArtifactDigest());
        attempt.setArtifactDigest(validation.getArtifactDigest());
        attempt.setVersionDigest(validation.getVersionDigest());
        attempt.setStatus("SUCCEEDED");
        attempt.setFailureStage(null);
        attempt.setFailureCode(null);
        attempt.setFailureReason(null);
    }

    private void recordFailure(SkillImportAttemptEntity attempt, String stage,
                               String code, RuntimeException exception,
                               SkillPackageValidationVO validation) {
        transactionOperations.execute(status -> {
            attempt.setAssetId(null);
            attempt.setStatus("FAILED");
            attempt.setFailureStage(stage);
            attempt.setFailureCode(code);
            attempt.setFailureReason(safeMessage(exception));
            if (validation != null) {
                attempt.setArtifactDigest(validation.getArtifactDigest());
                attempt.setVersionDigest(validation.getVersionDigest());
            }
            attemptMapper.updateResult(attempt);
            return null;
        });
    }

    private void verifyClientDigest(String clientDigest, String actualDigest) {
        if (!blank(clientDigest) && !actualDigest.equalsIgnoreCase(clientDigest.trim())) {
            throw new SkillPackageValidationException(
                    "CLIENT_DIGEST_MISMATCH", "客户端制品摘要与服务端计算结果不一致");
        }
    }

    private void validateCommand(SkillPackageImportCommand command, String actor) {
        if (command == null || blank(command.getRequestId()) || command.getOwnerScopeId() == null
                || command.getOwnerScopeId() <= 0 || blank(command.getSourceLocator())
                || command.getPackageBytes() == null || command.getPackageBytes().length == 0
                || blank(command.getFilename()) || blank(actor)) {
            throw new IllegalArgumentException("Skill 包导入信息不完整");
        }
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

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 2048));
    }

    private ImportAttemptVO toVO(SkillImportAttemptEntity attempt, String lifecycleState,
                                 boolean duplicate, SkillPackageValidationVO validation) {
        return new ImportAttemptVO(attempt.getRequestId(), attempt.getStatus(), attempt.getAssetId(),
                attempt.getArtifactDigest(), attempt.getVersionDigest(), lifecycleState, duplicate, validation,
                attempt.getFailureStage(), attempt.getFailureCode(), attempt.getFailureReason());
    }

    private static class ResolvedMetadata {
        private final String assetKey;
        private final String name;
        private final String description;
        private final String versionLabel;

        private ResolvedMetadata(String assetKey, String name, String description, String versionLabel) {
            this.assetKey = assetKey;
            this.name = name;
            this.description = description;
            this.versionLabel = versionLabel;
        }
    }
}
