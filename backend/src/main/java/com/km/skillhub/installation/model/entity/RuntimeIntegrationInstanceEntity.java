package com.km.skillhub.installation.model.entity;

import java.time.OffsetDateTime;

public class RuntimeIntegrationInstanceEntity {
    private Long id;
    private String integrationId;
    private Long scopeId;
    private String runtimeKey;
    private String runtimeVersion;
    private String targetKey;
    private String adapterVersion;
    private String configurationDigest;
    private String installationState;
    private String healthStatus;
    private Integer lastEventSequence;
    private String failureStage;
    private String errorCode;
    private String errorReason;
    private OffsetDateTime lastReportedAt;
    private String createdBy;
    private String updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long rowVersion;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getIntegrationId() { return integrationId; }
    public void setIntegrationId(String value) { integrationId = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public void setRuntimeVersion(String value) { runtimeVersion = value; }
    public String getTargetKey() { return targetKey; }
    public void setTargetKey(String value) { targetKey = value; }
    public String getAdapterVersion() { return adapterVersion; }
    public void setAdapterVersion(String value) { adapterVersion = value; }
    public String getConfigurationDigest() { return configurationDigest; }
    public void setConfigurationDigest(String value) { configurationDigest = value; }
    public String getInstallationState() { return installationState; }
    public void setInstallationState(String value) { installationState = value; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String value) { healthStatus = value; }
    public Integer getLastEventSequence() { return lastEventSequence; }
    public void setLastEventSequence(Integer value) { lastEventSequence = value; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String value) { failureStage = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String value) { errorReason = value; }
    public OffsetDateTime getLastReportedAt() { return lastReportedAt; }
    public void setLastReportedAt(OffsetDateTime value) { lastReportedAt = value; }
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
