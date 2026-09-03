package com.km.skillhub.installation.model.vo;

import com.km.skillhub.installation.model.entity.InstallationOperationEntity;

public class InstallationOperationVO {
    private Long id;
    private String operationId;
    private String requestId;
    private String operationType;
    private Long installationInstanceId;
    private String previousVersionDigest;
    private String targetVersionDigest;
    private String operationStage;
    private String operationState;
    private String rollbackState;
    private String failureStage;
    private String errorCode;
    private String errorReason;

    public static InstallationOperationVO from(InstallationOperationEntity entity) {
        InstallationOperationVO value = new InstallationOperationVO();
        value.id = entity.getId(); value.operationId = entity.getOperationId(); value.requestId = entity.getRequestId();
        value.operationType = entity.getOperationType(); value.installationInstanceId = entity.getInstallationInstanceId();
        value.previousVersionDigest = entity.getPreviousVersionDigest(); value.targetVersionDigest = entity.getTargetVersionDigest();
        value.operationStage = entity.getOperationStage(); value.operationState = entity.getOperationState();
        value.rollbackState = entity.getRollbackState(); value.failureStage = entity.getFailureStage();
        value.errorCode = entity.getErrorCode(); value.errorReason = entity.getErrorReason();
        return value;
    }

    public Long getId() { return id; }
    public String getOperationId() { return operationId; }
    public String getRequestId() { return requestId; }
    public String getOperationType() { return operationType; }
    public Long getInstallationInstanceId() { return installationInstanceId; }
    public String getPreviousVersionDigest() { return previousVersionDigest; }
    public String getTargetVersionDigest() { return targetVersionDigest; }
    public String getOperationStage() { return operationStage; }
    public String getOperationState() { return operationState; }
    public String getRollbackState() { return rollbackState; }
    public String getFailureStage() { return failureStage; }
    public String getErrorCode() { return errorCode; }
    public String getErrorReason() { return errorReason; }
}
