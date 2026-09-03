package com.km.skillhub.installation.model.entity;

import java.time.OffsetDateTime;

public class InstallationOperationEntity {
    private Long id;
    private String operationId;
    private String requestId;
    private String operationType;
    private Long installationInstanceId;
    private Long assetId;
    private String previousVersionDigest;
    private String targetVersionDigest;
    private String runtimeKey;
    private String runtimeVersion;
    private Long scopeId;
    private String operationStage;
    private String operationState;
    private String rollbackState;
    private String failureStage;
    private String errorCode;
    private String errorReason;
    private String actorId;
    private OffsetDateTime requestedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime createdAt;
    private Long rowVersion;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getOperationId() { return operationId; }
    public void setOperationId(String value) { operationId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String value) { operationType = value; }
    public Long getInstallationInstanceId() { return installationInstanceId; }
    public void setInstallationInstanceId(Long value) { installationInstanceId = value; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long value) { assetId = value; }
    public String getPreviousVersionDigest() { return previousVersionDigest; }
    public void setPreviousVersionDigest(String value) { previousVersionDigest = value; }
    public String getTargetVersionDigest() { return targetVersionDigest; }
    public void setTargetVersionDigest(String value) { targetVersionDigest = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public void setRuntimeVersion(String value) { runtimeVersion = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getOperationStage() { return operationStage; }
    public void setOperationStage(String value) { operationStage = value; }
    public String getOperationState() { return operationState; }
    public void setOperationState(String value) { operationState = value; }
    public String getRollbackState() { return rollbackState; }
    public void setRollbackState(String value) { rollbackState = value; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String value) { failureStage = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String value) { errorReason = value; }
    public String getActorId() { return actorId; }
    public void setActorId(String value) { actorId = value; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(OffsetDateTime value) { requestedAt = value; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime value) { startedAt = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long value) { rowVersion = value; }
}
