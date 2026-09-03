package com.km.skillhub.asset.model.entity;

public class SkillArtifactEntity {
    private Long id;
    private Long assetId;
    private String artifactUri;
    private String artifactDigest;
    private String mediaType;
    private Long sizeBytes;
    private String sourceSnapshotUri;
    private String encryptionStatus;
    private String createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getArtifactUri() { return artifactUri; }
    public void setArtifactUri(String artifactUri) { this.artifactUri = artifactUri; }
    public String getArtifactDigest() { return artifactDigest; }
    public void setArtifactDigest(String artifactDigest) { this.artifactDigest = artifactDigest; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSourceSnapshotUri() { return sourceSnapshotUri; }
    public void setSourceSnapshotUri(String sourceSnapshotUri) { this.sourceSnapshotUri = sourceSnapshotUri; }
    public String getEncryptionStatus() { return encryptionStatus; }
    public void setEncryptionStatus(String encryptionStatus) { this.encryptionStatus = encryptionStatus; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
