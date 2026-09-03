package com.km.skillhub.asset.model.dto;

public class SkillPackageImportCommand {
    private String requestId;
    private String assetKey;
    private String name;
    private String description;
    private Long ownerScopeId;
    private String versionLabel;
    private String sourceLocator;
    private byte[] packageBytes;
    private String filename;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getAssetKey() { return assetKey; }
    public void setAssetKey(String assetKey) { this.assetKey = assetKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getOwnerScopeId() { return ownerScopeId; }
    public void setOwnerScopeId(Long ownerScopeId) { this.ownerScopeId = ownerScopeId; }
    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel; }
    public String getSourceLocator() { return sourceLocator; }
    public void setSourceLocator(String sourceLocator) { this.sourceLocator = sourceLocator; }
    public byte[] getPackageBytes() { return packageBytes; }
    public void setPackageBytes(byte[] packageBytes) { this.packageBytes = packageBytes; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
}
