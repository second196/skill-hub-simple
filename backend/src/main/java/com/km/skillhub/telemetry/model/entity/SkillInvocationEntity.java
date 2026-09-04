package com.km.skillhub.telemetry.model.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class SkillInvocationEntity {
    private Long id;
    private Long scopeId;
    private String invocationId;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String runtimeKey;
    private String skillName;
    private String versionDigest;
    private Boolean versionUnknown;
    private String versionUnknownReason;
    private String trackerVersion;
    private String triggerType;
    private String status;
    private Long durationMs;
    private Long inputTokens;
    private Long outputTokens;
    private BigDecimal cost;
    private OffsetDateTime occurredAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getInvocationId() { return invocationId; }
    public void setInvocationId(String value) { invocationId = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId = value; }
    public String getSpanId() { return spanId; }
    public void setSpanId(String value) { spanId = value; }
    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String value) { parentSpanId = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String value) { skillName = value; }
    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String value) { versionDigest = value; }
    public Boolean getVersionUnknown() { return versionUnknown; }
    public void setVersionUnknown(Boolean value) { versionUnknown = value; }
    public String getVersionUnknownReason() { return versionUnknownReason; }
    public void setVersionUnknownReason(String value) { versionUnknownReason = value; }
    public String getTrackerVersion() { return trackerVersion; }
    public void setTrackerVersion(String value) { trackerVersion = value; }
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
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime value) { occurredAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
}
