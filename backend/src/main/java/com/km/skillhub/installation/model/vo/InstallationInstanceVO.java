package com.km.skillhub.installation.model.vo;

import com.km.skillhub.installation.model.entity.InstallationInstanceEntity;

public class InstallationInstanceVO {
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
    private String lastErrorCode;
    private String lastErrorReason;

    public static InstallationInstanceVO from(InstallationInstanceEntity entity) {
        InstallationInstanceVO value = new InstallationInstanceVO();
        value.id = entity.getId(); value.assetId = entity.getAssetId();
        value.currentVersionDigest = entity.getCurrentVersionDigest(); value.runtimeKey = entity.getRuntimeKey();
        value.runtimeVersion = entity.getRuntimeVersion(); value.targetType = entity.getTargetType();
        value.targetKey = entity.getTargetKey(); value.scopeId = entity.getScopeId();
        value.desiredState = entity.getDesiredState(); value.skillState = entity.getSkillState();
        value.trackerState = entity.getTrackerState(); value.overallState = entity.getOverallState();
        value.healthStatus = entity.getHealthStatus(); value.lastErrorCode = entity.getLastErrorCode();
        value.lastErrorReason = entity.getLastErrorReason();
        return value;
    }

    public Long getId() { return id; }
    public Long getAssetId() { return assetId; }
    public String getCurrentVersionDigest() { return currentVersionDigest; }
    public String getRuntimeKey() { return runtimeKey; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public String getTargetType() { return targetType; }
    public String getTargetKey() { return targetKey; }
    public Long getScopeId() { return scopeId; }
    public String getDesiredState() { return desiredState; }
    public String getSkillState() { return skillState; }
    public String getTrackerState() { return trackerState; }
    public String getOverallState() { return overallState; }
    public String getHealthStatus() { return healthStatus; }
    public String getLastErrorCode() { return lastErrorCode; }
    public String getLastErrorReason() { return lastErrorReason; }
}
