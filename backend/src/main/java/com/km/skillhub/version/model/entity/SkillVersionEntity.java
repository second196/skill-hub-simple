package com.km.skillhub.version.model.entity;

public class SkillVersionEntity {
    private Long id;
    private Long assetId;
    private Long artifactId;
    private String versionLabel;
    private String versionDigest;
    private String sourceType;
    private String sourceLocator;
    private String sourceSnapshotUri;
    private String metadataStatus;
    private String lifecycleState;
    private String createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public Long getArtifactId() { return artifactId; }
    public void setArtifactId(Long artifactId) { this.artifactId = artifactId; }
    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel; }
    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String versionDigest) { this.versionDigest = versionDigest; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceLocator() { return sourceLocator; }
    public void setSourceLocator(String sourceLocator) { this.sourceLocator = sourceLocator; }
    public String getSourceSnapshotUri() { return sourceSnapshotUri; }
    public void setSourceSnapshotUri(String sourceSnapshotUri) { this.sourceSnapshotUri = sourceSnapshotUri; }
    public String getMetadataStatus() { return metadataStatus; }
    public void setMetadataStatus(String metadataStatus) { this.metadataStatus = metadataStatus; }
    public String getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(String lifecycleState) { this.lifecycleState = lifecycleState; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
