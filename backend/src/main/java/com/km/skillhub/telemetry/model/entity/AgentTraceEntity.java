package com.km.skillhub.telemetry.model.entity;

import java.time.OffsetDateTime;

public class AgentTraceEntity {
    private Long id;
    private Long scopeId;
    private String traceId;
    private String sessionId;
    private String runtimeKey;
    private String runtimeVersion;
    private String agentId;
    private String parentAgentId;
    private String status;
    private Integer eventCount;
    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    private String completeness;
    private OffsetDateTime updatedAt;
    private Long rowVersion;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public void setRuntimeVersion(String value) { runtimeVersion = value; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String value) { agentId = value; }
    public String getParentAgentId() { return parentAgentId; }
    public void setParentAgentId(String value) { parentAgentId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getEventCount() { return eventCount; }
    public void setEventCount(Integer value) { eventCount = value; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime value) { startedAt = value; }
    public OffsetDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(OffsetDateTime value) { endedAt = value; }
    public String getCompleteness() { return completeness; }
    public void setCompleteness(String value) { completeness = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long value) { rowVersion = value; }
}
