package com.km.skillhub.installation.model.vo;

import com.km.skillhub.installation.model.entity.RuntimeIntegrationInstanceEntity;

import java.time.OffsetDateTime;

public class RuntimeIntegrationVO {
    private final String integrationId;
    private final Long scopeId;
    private final String runtimeKey;
    private final String runtimeVersion;
    private final String targetKey;
    private final String adapterVersion;
    private final String configurationDigest;
    private final String installationState;
    private final String healthStatus;
    private final Integer lastEventSequence;
    private final String failureStage;
    private final String errorCode;
    private final String errorReason;
    private final OffsetDateTime lastReportedAt;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public RuntimeIntegrationVO(RuntimeIntegrationInstanceEntity entity) {
        integrationId = entity.getIntegrationId();
        scopeId = entity.getScopeId();
        runtimeKey = entity.getRuntimeKey();
        runtimeVersion = entity.getRuntimeVersion();
        targetKey = entity.getTargetKey();
        adapterVersion = entity.getAdapterVersion();
        configurationDigest = entity.getConfigurationDigest();
        installationState = entity.getInstallationState();
        healthStatus = entity.getHealthStatus();
        lastEventSequence = entity.getLastEventSequence();
        failureStage = entity.getFailureStage();
        errorCode = entity.getErrorCode();
        errorReason = entity.getErrorReason();
        lastReportedAt = entity.getLastReportedAt();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();
    }

    public String getIntegrationId() { return integrationId; }
    public Long getScopeId() { return scopeId; }
    public String getRuntimeKey() { return runtimeKey; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public String getTargetKey() { return targetKey; }
    public String getAdapterVersion() { return adapterVersion; }
    public String getConfigurationDigest() { return configurationDigest; }
    public String getInstallationState() { return installationState; }
    public String getHealthStatus() { return healthStatus; }
    public Integer getLastEventSequence() { return lastEventSequence; }
    public String getFailureStage() { return failureStage; }
    public String getErrorCode() { return errorCode; }
    public String getErrorReason() { return errorReason; }
    public OffsetDateTime getLastReportedAt() { return lastReportedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
