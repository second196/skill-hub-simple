package com.km.skillhub.asset.service.impl;

import com.km.skillhub.asset.model.dto.AssetImportRequest;
import com.km.skillhub.asset.model.entity.SkillArtifactEntity;
import com.km.skillhub.asset.model.entity.SkillAssetEntity;
import com.km.skillhub.asset.model.entity.SkillImportAttemptEntity;
import com.km.skillhub.asset.model.vo.ImportAttemptVO;
import com.km.skillhub.asset.service.AssetImportService;
import com.km.skillhub.integration.artifact.ArtifactStore;
import com.km.skillhub.mapper.asset.SkillArtifactMapper;
import com.km.skillhub.mapper.asset.SkillAssetMapper;
import com.km.skillhub.mapper.asset.SkillImportAttemptMapper;
import com.km.skillhub.mapper.content.SkillVersionFileMapper;
import com.km.skillhub.content.model.entity.SkillVersionFileEntity;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class AssetImportServiceImpl implements AssetImportService {

    private final SkillImportAttemptMapper attemptMapper;
    private final SkillAssetMapper assetMapper;
    private final SkillArtifactMapper artifactMapper;
    private final SkillVersionMapper versionMapper;
    private final SkillVersionFileMapper fileMapper;
    private final ArtifactStore artifactStore;

    public AssetImportServiceImpl(SkillImportAttemptMapper attemptMapper,
                                  SkillAssetMapper assetMapper,
                                  SkillArtifactMapper artifactMapper,
                                  SkillVersionMapper versionMapper,
                                  SkillVersionFileMapper fileMapper,
                                  ArtifactStore artifactStore) {
        this.attemptMapper = attemptMapper;
        this.assetMapper = assetMapper;
        this.artifactMapper = artifactMapper;
        this.versionMapper = versionMapper;
        this.fileMapper = fileMapper;
        this.artifactStore = artifactStore;
    }

    @Override
    @Transactional
    public ImportAttemptVO importAsset(AssetImportRequest request, String actor) {
        validateRequest(request);
        SkillImportAttemptEntity existing = attemptMapper.findByRequestId(request.getRequestId());
        if (existing != null) {
            return toVO(existing);
        }

        SkillImportAttemptEntity attempt = new SkillImportAttemptEntity();
        attempt.setRequestId(request.getRequestId());
        attempt.setSourceType(request.getSourceType());
        attempt.setSourceLocator(request.getSourceLocator());
        attempt.setStatus("STARTED");
        attempt.setCreatedBy(actor);
        attemptMapper.insertAttempt(attempt);

        try {
            String digest = sha256(request.getContent());
            ArtifactStore.ArtifactLocation location = artifactStore.store(request.getContent(), digest);
            SkillAssetEntity asset = assetMapper.findByAssetKey(request.getAssetKey());
            if (asset == null) {
                asset = new SkillAssetEntity();
                asset.setAssetKey(request.getAssetKey());
                asset.setName(request.getName());
                asset.setDescription(request.getDescription());
                asset.setOwnerScopeId(request.getOwnerScopeId());
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
            artifact.setSourceSnapshotUri(request.getSourceLocator());
            artifact.setEncryptionStatus("UNKNOWN");
            artifact.setCreatedBy(actor);
            artifactMapper.insertArtifact(artifact);

            SkillVersionEntity version = new SkillVersionEntity();
            version.setAssetId(asset.getId());
            version.setArtifactId(artifact.getId());
            version.setVersionLabel(request.getVersionLabel());
            version.setVersionDigest(digest);
            version.setSourceType(request.getSourceType());
            version.setSourceLocator(request.getSourceLocator());
            version.setSourceSnapshotUri(request.getSourceLocator());
            version.setMetadataStatus("COMPLETE");
            version.setLifecycleState("CANDIDATE");
            version.setCreatedBy(actor);
            versionMapper.insertVersion(version);

            SkillVersionFileEntity manifest = new SkillVersionFileEntity();
            manifest.setVersionId(version.getId());
            manifest.setPath(request.getSourceLocator());
            manifest.setRequired(true);
            manifest.setReadStatus("READABLE");
            manifest.setContentDigest(digest);
            fileMapper.insertFile(manifest);

            attempt.setAssetId(asset.getId());
            attempt.setRequestDigest(digest);
            attempt.setArtifactDigest(digest);
            attempt.setVersionDigest(digest);
            attempt.setStatus("SUCCEEDED");
            attemptMapper.updateResult(attempt);
            return toVO(attempt, digest);
        } catch (RuntimeException exception) {
            attempt.setStatus("FAILED");
            attempt.setFailureStage("PERSISTENCE");
            attempt.setFailureCode("IMPORT_FAILED");
            attempt.setFailureReason(safeMessage(exception));
            attemptMapper.updateResult(attempt);
            return toVO(attempt);
        }
    }

    @Override
    public ImportAttemptVO getImportAttempt(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("Request ID is required");
        }
        SkillImportAttemptEntity attempt = attemptMapper.findByRequestId(requestId);
        if (attempt == null) {
            throw new IllegalArgumentException("Import attempt not found");
        }
        return toVO(attempt);
    }

    private void validateRequest(AssetImportRequest request) {
        if (request == null || blank(request.getRequestId()) || blank(request.getAssetKey())
                || blank(request.getName()) || blank(request.getDescription())
                || request.getOwnerScopeId() == null || blank(request.getVersionLabel())
                || blank(request.getSourceType()) || blank(request.getSourceLocator())
                || blank(request.getContent())) {
            throw new IllegalArgumentException("Import request is incomplete");
        }
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 2048));
    }

    private ImportAttemptVO toVO(SkillImportAttemptEntity attempt) {
        SkillVersionEntity version = attempt.getVersionDigest() == null
                ? null : versionMapper.findByDigest(attempt.getVersionDigest());
        return new ImportAttemptVO(attempt.getRequestId(), attempt.getStatus(), attempt.getAssetId(),
                attempt.getArtifactDigest(), attempt.getVersionDigest(),
                version == null ? null : version.getLifecycleState(), false, null,
                attempt.getFailureStage(), attempt.getFailureCode(), attempt.getFailureReason());
    }

    private ImportAttemptVO toVO(SkillImportAttemptEntity attempt, String digest) {
        return new ImportAttemptVO(attempt.getRequestId(), attempt.getStatus(), attempt.getAssetId(), digest,
                digest, "CANDIDATE", false, null, attempt.getFailureStage(), attempt.getFailureCode(),
                attempt.getFailureReason());
    }
}
