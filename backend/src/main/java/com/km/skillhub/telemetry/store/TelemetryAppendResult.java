package com.km.skillhub.telemetry.store;

public class TelemetryAppendResult {
    private final Long batchId;
    private final int accepted;
    private final int duplicate;
    private final int rejected;
    private final String status;

    public TelemetryAppendResult(Long batchId, int accepted, int duplicate, int rejected, String status) {
        this.batchId = batchId;
        this.accepted = accepted;
        this.duplicate = duplicate;
        this.rejected = rejected;
        this.status = status;
    }

    public Long getBatchId() {
        return batchId;
    }

    public int getAccepted() {
        return accepted;
    }

    public int getDuplicate() {
        return duplicate;
    }

    public int getRejected() {
        return rejected;
    }

    public String getStatus() {
        return status;
    }
}
