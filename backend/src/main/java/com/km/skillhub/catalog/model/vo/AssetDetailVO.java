package com.km.skillhub.catalog.model.vo;

public class AssetDetailVO {
    private Long assetId;
    private String assetKey;
    private String name;
    private String description;
    private Long ownerScopeId;
    private String status;
    private String versionLabel;
    private String versionDigest;
    private String lifecycleState;
    private String metadataStatus;

    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getAssetKey() { return assetKey; }
    public void setAssetKey(String assetKey) { this.assetKey = assetKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getOwnerScopeId() { return ownerScopeId; }
    public void setOwnerScopeId(Long ownerScopeId) { this.ownerScopeId = ownerScopeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel; }
    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String versionDigest) { this.versionDigest = versionDigest; }
    public String getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(String lifecycleState) { this.lifecycleState = lifecycleState; }
    public String getMetadataStatus() { return metadataStatus; }
    public void setMetadataStatus(String metadataStatus) { this.metadataStatus = metadataStatus; }
}
