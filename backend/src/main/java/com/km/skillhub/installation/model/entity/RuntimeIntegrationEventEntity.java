package com.km.skillhub.installation.model.entity;

import java.time.OffsetDateTime;

public class RuntimeIntegrationEventEntity {
    private Long id;
    private String eventId;
    private Long runtimeIntegrationInstanceId;
    private Integer eventSequence;
    private String eventType;
    private String stage;
    private String result;
    private String installationState;
    private String healthStatus;
    private String failureStage;
    private String errorCode;
    private String errorReason;
    private OffsetDateTime occurredAt;
    private OffsetDateTime receivedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public Long getRuntimeIntegrationInstanceId() { return runtimeIntegrationInstanceId; }
    public void setRuntimeIntegrationInstanceId(Long value) { runtimeIntegrationInstanceId = value; }
    public Integer getEventSequence() { return eventSequence; }
    public void setEventSequence(Integer value) { eventSequence = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public String getStage() { return stage; }
    public void setStage(String value) { stage = value; }
    public String getResult() { return result; }
    public void setResult(String value) { result = value; }
    public String getInstallationState() { return installationState; }
    public void setInstallationState(String value) { installationState = value; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String value) { healthStatus = value; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String value) { failureStage = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String value) { errorReason = value; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime value) { occurredAt = value; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime value) { receivedAt = value; }
}
