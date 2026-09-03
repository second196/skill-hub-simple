package com.km.skillhub.integration.downstream;

public class ReleaseEvent {
    private final String eventType;
    private final String versionDigest;
    private final Long decisionId;
    private final String scopeType;
    private final Long scopeId;
    private final String policyVersion;
    public ReleaseEvent(String eventType, String versionDigest, Long decisionId, String scopeType,
                        Long scopeId, String policyVersion) {
        this.eventType = eventType; this.versionDigest = versionDigest; this.decisionId = decisionId;
        this.scopeType = scopeType; this.scopeId = scopeId; this.policyVersion = policyVersion;
    }
    public String getEventType() { return eventType; }
    public String getVersionDigest() { return versionDigest; }
    public Long getDecisionId() { return decisionId; }
    public String getScopeType() { return scopeType; }
    public Long getScopeId() { return scopeId; }
    public String getPolicyVersion() { return policyVersion; }
}
