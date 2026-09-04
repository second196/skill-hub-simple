package com.km.skillhub.telemetry.model.entity;

import java.time.OffsetDateTime;

public class TelemetryAggregationOutboxEntity {
    private Long id;
    private String eventId;
    private Long batchId;
    private String eventType;
    private String payload;
    private String deliveryState;
    private Integer attempts;
    private OffsetDateTime nextAttemptAt;
    private OffsetDateTime deliveredAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long value) { batchId = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public String getPayload() { return payload; }
    public void setPayload(String value) { payload = value; }
    public String getDeliveryState() { return deliveryState; }
    public void setDeliveryState(String value) { deliveryState = value; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer value) { attempts = value; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime value) { nextAttemptAt = value; }
    public OffsetDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(OffsetDateTime value) { deliveredAt = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
}
