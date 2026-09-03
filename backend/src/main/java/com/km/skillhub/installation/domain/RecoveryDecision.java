package com.km.skillhub.installation.domain;

public class RecoveryDecision {
    private final String operationId;
    private final String decision;
    private final String rollbackVersionDigest;
    private final String status;

    public RecoveryDecision(String operationId, String decision, String rollbackVersionDigest, String status) {
        this.operationId = operationId;
        this.decision = decision;
        this.rollbackVersionDigest = rollbackVersionDigest;
        this.status = status;
    }

    public String getOperationId() { return operationId; }
    public String getDecision() { return decision; }
    public String getRollbackVersionDigest() { return rollbackVersionDigest; }
    public String getStatus() { return status; }
}
