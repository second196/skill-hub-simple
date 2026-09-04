package com.km.skillhub.telemetry.model.vo;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;

public class TelemetryIngestBatchVO {
    private final Long batchId;
    private final String requestId;
    private final Long scopeId;
    private final String runtimeKey;
    private final int received;
    private final int accepted;
    private final int duplicate;
    private final int rejected;
    private final String status;
    private final Map<String, Integer> rejectionReasons;
    private final OffsetDateTime receivedAt;
    private final OffsetDateTime completedAt;

    public TelemetryIngestBatchVO(Long batchId, String requestId, Long scopeId, String runtimeKey,
                                  int received, int accepted, int duplicate, int rejected, String status,
                                  Map<String, Integer> rejectionReasons, OffsetDateTime receivedAt,
                                  OffsetDateTime completedAt) {
        this.batchId = batchId;
        this.requestId = requestId;
        this.scopeId = scopeId;
        this.runtimeKey = runtimeKey;
        this.received = received;
        this.accepted = accepted;
        this.duplicate = duplicate;
        this.rejected = rejected;
        this.status = status;
        this.rejectionReasons = rejectionReasons == null
                ? Collections.<String, Integer>emptyMap() : rejectionReasons;
        this.receivedAt = receivedAt;
        this.completedAt = completedAt;
    }

    public Long getBatchId() { return batchId; }
    public String getRequestId() { return requestId; }
    public Long getScopeId() { return scopeId; }
    public String getRuntimeKey() { return runtimeKey; }
    public int getReceived() { return received; }
    public int getAccepted() { return accepted; }
    public int getDuplicate() { return duplicate; }
    public int getRejected() { return rejected; }
    public String getStatus() { return status; }
    public Map<String, Integer> getRejectionReasons() { return rejectionReasons; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
