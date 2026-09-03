package com.km.skillhub.installation.model.entity;

import java.time.OffsetDateTime;

public class InstallationInstanceEntity {
    private Long id;
    private Long assetId;
    private String currentVersionDigest;
    private String runtimeKey;
    private String runtimeVersion;
    private String targetType;
    private String targetKey;
    private Long scopeId;
    private String desiredState;
    private String skillState;
    private String trackerState;
    private String overallState;
    private String healthStatus;
    private OffsetDateTime lastHealthAt;
    private String lastErrorCode;
    private String lastErrorReason;
    private OffsetDateTime installedAt;
    private String createdBy;
    private String updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long rowVersion;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getCurrentVersionDigest() { return currentVersionDigest; }
    public void setCurrentVersionDigest(String value) { currentVersionDigest = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public void setRuntimeVersion(String value) { runtimeVersion = value; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String value) { targetType = value; }
    public String getTargetKey() { return targetKey; }
    public void setTargetKey(String value) { targetKey = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getDesiredState() { return desiredState; }
    public void setDesiredState(String value) { desiredState = value; }
    public String getSkillState() { return skillState; }
    public void setSkillState(String value) { skillState = value; }
    public String getTrackerState() { return trackerState; }
    public void setTrackerState(String value) { trackerState = value; }
    public String getOverallState() { return overallState; }
    public void setOverallState(String value) { overallState = value; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String value) { healthStatus = value; }
    public OffsetDateTime getLastHealthAt() { return lastHealthAt; }
    public void setLastHealthAt(OffsetDateTime value) { lastHealthAt = value; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String value) { lastErrorCode = value; }
    public String getLastErrorReason() { return lastErrorReason; }
    public void setLastErrorReason(String value) { lastErrorReason = value; }
    public OffsetDateTime getInstalledAt() { return installedAt; }
    public void setInstalledAt(OffsetDateTime value) { installedAt = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String value) { updatedBy = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long value) { rowVersion = value; }
}
