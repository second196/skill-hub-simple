package com.km.skillhub.telemetry.model.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class RuntimeEventEntity {
    private Long id;
    private String eventId;
    private Long batchId;
    private String schemaVersion;
    private String eventType;
    private Long scopeId;
    private String runtimeKey;
    private String runtimeVersion;
    private String trackerVersion;
    private String sessionId;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private Long sequence;
    private String agentId;
    private String parentAgentId;
    private String modelName;
    private String toolName;
    private String mcpServer;
    private String skillName;
    private String versionDigest;
    private String invocationId;
    private String triggerType;
    private String status;
    private Long durationMs;
    private Long inputTokens;
    private Long outputTokens;
    private BigDecimal cost;
    private Boolean versionUnknown;
    private String versionUnknownReason;
    private String attributes;
    private String missingFields;
    private String privacyActions;
    private OffsetDateTime occurredAt;
    private OffsetDateTime receivedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long value) { batchId = value; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public void setRuntimeVersion(String value) { runtimeVersion = value; }
    public String getTrackerVersion() { return trackerVersion; }
    public void setTrackerVersion(String value) { trackerVersion = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId = value; }
    public String getSpanId() { return spanId; }
    public void setSpanId(String value) { spanId = value; }
    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String value) { parentSpanId = value; }
    public Long getSequence() { return sequence; }
    public void setSequence(Long value) { sequence = value; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String value) { agentId = value; }
    public String getParentAgentId() { return parentAgentId; }
    public void setParentAgentId(String value) { parentAgentId = value; }
    public String getModelName() { return modelName; }
    public void setModelName(String value) { modelName = value; }
    public String getToolName() { return toolName; }
    public void setToolName(String value) { toolName = value; }
    public String getMcpServer() { return mcpServer; }
    public void setMcpServer(String value) { mcpServer = value; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String value) { skillName = value; }
    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String value) { versionDigest = value; }
    public String getInvocationId() { return invocationId; }
    public void setInvocationId(String value) { invocationId = value; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String value) { triggerType = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long value) { durationMs = value; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long value) { inputTokens = value; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long value) { outputTokens = value; }
    public BigDecimal getCost() { return cost; }
    public void setCost(BigDecimal value) { cost = value; }
    public Boolean getVersionUnknown() { return versionUnknown; }
    public void setVersionUnknown(Boolean value) { versionUnknown = value; }
    public String getVersionUnknownReason() { return versionUnknownReason; }
    public void setVersionUnknownReason(String value) { versionUnknownReason = value; }
    public String getAttributes() { return attributes; }
    public void setAttributes(String value) { attributes = value; }
    public String getMissingFields() { return missingFields; }
    public void setMissingFields(String value) { missingFields = value; }
    public String getPrivacyActions() { return privacyActions; }
    public void setPrivacyActions(String value) { privacyActions = value; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime value) { occurredAt = value; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime value) { receivedAt = value; }
}
