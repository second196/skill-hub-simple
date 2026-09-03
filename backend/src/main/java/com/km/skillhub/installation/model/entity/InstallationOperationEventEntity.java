package com.km.skillhub.installation.model.entity;

import java.time.OffsetDateTime;

public class InstallationOperationEventEntity {
    private Long id;
    private String eventId;
    private Long operationId;
    private Integer eventSequence;
    private String eventType;
    private String eventState;
    private String payload;
    private OffsetDateTime occurredAt;
    private OffsetDateTime receivedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public Long getOperationId() { return operationId; }
    public void setOperationId(Long value) { operationId = value; }
    public Integer getEventSequence() { return eventSequence; }
    public void setEventSequence(Integer value) { eventSequence = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public String getEventState() { return eventState; }
    public void setEventState(String value) { eventState = value; }
    public String getPayload() { return payload; }
    public void setPayload(String value) { payload = value; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime value) { occurredAt = value; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime value) { receivedAt = value; }
}
