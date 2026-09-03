package com.km.skillhub.installation.model.dto;

public class InstallationRequest {
    private Long assetId;
    private String versionDigest;
    private String scopeType;
    private Long scopeId;
    private String runtimeKey;
    private String runtimeVersion;
    private String targetType;
    private String targetKey;
    private String trackerKey;
    private String trackerVersion;
    private String trackerConfigurationDigest;

    public Long getAssetId() { return assetId; }
    public void setAssetId(Long value) { assetId = value; }
    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String value) { versionDigest = value; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String value) { scopeType = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public void setRuntimeVersion(String value) { runtimeVersion = value; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String value) { targetType = value; }
    public String getTargetKey() { return targetKey; }
    public void setTargetKey(String value) { targetKey = value; }
    public String getTrackerKey() { return trackerKey; }
    public void setTrackerKey(String value) { trackerKey = value; }
    public String getTrackerVersion() { return trackerVersion; }
    public void setTrackerVersion(String value) { trackerVersion = value; }
    public String getTrackerConfigurationDigest() { return trackerConfigurationDigest; }
    public void setTrackerConfigurationDigest(String value) { trackerConfigurationDigest = value; }
}
