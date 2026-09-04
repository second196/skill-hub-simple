package com.km.skillhub.telemetry.model.entity;

import java.time.OffsetDateTime;

public class TelemetryIngestBatchEntity {
    private Long id;
    private String requestId;
    private String actorId;
    private String tokenId;
    private Long scopeId;
    private String runtimeKey;
    private String payloadDigest;
    private Integer receivedCount;
    private Integer acceptedCount;
    private Integer duplicateCount;
    private Integer rejectedCount;
    private String status;
    private String errorSummary;
    private OffsetDateTime receivedAt;
    private OffsetDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getActorId() { return actorId; }
    public void setActorId(String value) { actorId = value; }
    public String getTokenId() { return tokenId; }
    public void setTokenId(String value) { tokenId = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public String getPayloadDigest() { return payloadDigest; }
    public void setPayloadDigest(String value) { payloadDigest = value; }
    public Integer getReceivedCount() { return receivedCount; }
    public void setReceivedCount(Integer value) { receivedCount = value; }
    public Integer getAcceptedCount() { return acceptedCount; }
    public void setAcceptedCount(Integer value) { acceptedCount = value; }
    public Integer getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(Integer value) { duplicateCount = value; }
    public Integer getRejectedCount() { return rejectedCount; }
    public void setRejectedCount(Integer value) { rejectedCount = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String value) { errorSummary = value; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime value) { receivedAt = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
}
