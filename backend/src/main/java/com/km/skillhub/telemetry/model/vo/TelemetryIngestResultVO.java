package com.km.skillhub.telemetry.model.vo;

import java.util.Collections;
import java.util.Map;

public class TelemetryIngestResultVO {
    private final String requestId;
    private final Long batchId;
    private final int accepted;
    private final int duplicate;
    private final int rejected;
    private final String status;
    private final Map<String, Integer> rejectionReasons;

    public TelemetryIngestResultVO(String requestId, Long batchId, int accepted, int duplicate,
                                   int rejected, String status, Map<String, Integer> rejectionReasons) {
        this.requestId = requestId;
        this.batchId = batchId;
        this.accepted = accepted;
        this.duplicate = duplicate;
        this.rejected = rejected;
        this.status = status;
        this.rejectionReasons = rejectionReasons == null
                ? Collections.<String, Integer>emptyMap() : rejectionReasons;
    }

    public String getRequestId() { return requestId; }
    public Long getBatchId() { return batchId; }
    public int getAccepted() { return accepted; }
    public int getDuplicate() { return duplicate; }
    public int getRejected() { return rejected; }
    public String getStatus() { return status; }
    public Map<String, Integer> getRejectionReasons() { return rejectionReasons; }
}
