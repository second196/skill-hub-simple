package com.km.skillhub.integration.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class InstallationEvent {
    private final String eventId;
    private final String operationId;
    private final Integer sequence;
    private final String eventType;
    private final Long instanceId;
    private final Long assetId;
    private final String versionDigest;
    private final String previousVersionDigest;
    private final String runtimeKey;
    private final String runtimeVersion;
    private final Long scopeId;
    private final String stage;
    private final String errorCode;
    private final String errorReason;

    @JsonCreator
    public InstallationEvent(@JsonProperty("eventId") String eventId,
                              @JsonProperty("operationId") String operationId,
                              @JsonProperty("sequence") Integer sequence,
                              @JsonProperty("eventType") String eventType,
                              @JsonProperty("instanceId") Long instanceId,
                              @JsonProperty("assetId") Long assetId,
                              @JsonProperty("versionDigest") String versionDigest,
                              @JsonProperty("previousVersionDigest") String previousVersionDigest,
                              @JsonProperty("runtimeKey") String runtimeKey,
                              @JsonProperty("runtimeVersion") String runtimeVersion,
                              @JsonProperty("scopeId") Long scopeId,
                              @JsonProperty("stage") String stage,
                              @JsonProperty("errorCode") String errorCode,
                              @JsonProperty("errorReason") String errorReason) {
        this.eventId = eventId; this.operationId = operationId; this.sequence = sequence; this.eventType = eventType;
        this.instanceId = instanceId; this.assetId = assetId; this.versionDigest = versionDigest;
        this.previousVersionDigest = previousVersionDigest; this.runtimeKey = runtimeKey;
        this.runtimeVersion = runtimeVersion; this.scopeId = scopeId; this.stage = stage;
        this.errorCode = errorCode; this.errorReason = errorReason;
    }

    public void validate() {
        if (blank(eventId) || blank(operationId) || sequence == null || sequence < 0 || blank(eventType)
                || instanceId == null || blank(versionDigest) || !versionDigest.matches("[0-9a-fA-F]{64}")
                || blank(runtimeKey) || blank(runtimeVersion) || scopeId == null || blank(stage)) {
            throw new IllegalArgumentException("Installation event is incomplete");
        }
        if ("latest".equalsIgnoreCase(versionDigest)) {
            throw new IllegalArgumentException("Installation event cannot use latest version");
        }
        if (previousVersionDigest != null && !previousVersionDigest.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Previous installation version must be a SHA-256 digest");
        }
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    public String getEventId() { return eventId; }
    public String getOperationId() { return operationId; }
    public Integer getSequence() { return sequence; }
    public String getEventType() { return eventType; }
    public Long getInstanceId() { return instanceId; }
    public Long getAssetId() { return assetId; }
    public String getVersionDigest() { return versionDigest; }
    public String getPreviousVersionDigest() { return previousVersionDigest; }
    public String getRuntimeKey() { return runtimeKey; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public Long getScopeId() { return scopeId; }
    public String getStage() { return stage; }
    public String getErrorCode() { return errorCode; }
    public String getErrorReason() { return errorReason; }
}
