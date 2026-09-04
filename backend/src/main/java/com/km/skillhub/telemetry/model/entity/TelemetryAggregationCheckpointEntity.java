package com.km.skillhub.telemetry.model.entity;

import java.time.OffsetDateTime;

public class TelemetryAggregationCheckpointEntity {
    private Long id;
    private String consumerKey;
    private Long lastBatchId;
    private OffsetDateTime windowStart;
    private OffsetDateTime windowEnd;
    private OffsetDateTime updatedAt;
    private Long rowVersion;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getConsumerKey() { return consumerKey; }
    public void setConsumerKey(String value) { consumerKey = value; }
    public Long getLastBatchId() { return lastBatchId; }
    public void setLastBatchId(Long value) { lastBatchId = value; }
    public OffsetDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(OffsetDateTime value) { windowStart = value; }
    public OffsetDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(OffsetDateTime value) { windowEnd = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long value) { rowVersion = value; }
}
