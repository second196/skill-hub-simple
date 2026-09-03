package com.km.skillhub.installation.model.dto;

public class RecoveryRequest {
    private String operationId;
    private String reason;

    public String getOperationId() { return operationId; }
    public void setOperationId(String value) { operationId = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
