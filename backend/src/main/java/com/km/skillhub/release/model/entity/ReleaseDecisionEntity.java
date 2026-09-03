package com.km.skillhub.release.model.entity;

import java.time.OffsetDateTime;

public class ReleaseDecisionEntity {
    private Long id;
    private String versionDigest;
    private String scopeType;
    private Long scopeId;
    private String policyVersion;
    private String releaseMode;
    private String decisionState;
    private String matchedRules;
    private String blockingReasons;
    private String requestId;
    private OffsetDateTime decidedAt;
    private String createdBy;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String versionDigest) { this.versionDigest = versionDigest; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getReleaseMode() { return releaseMode; }
    public void setReleaseMode(String releaseMode) { this.releaseMode = releaseMode; }
    public String getDecisionState() { return decisionState; }
    public void setDecisionState(String decisionState) { this.decisionState = decisionState; }
    public String getMatchedRules() { return matchedRules; }
    public void setMatchedRules(String matchedRules) { this.matchedRules = matchedRules; }
    public String getBlockingReasons() { return blockingReasons; }
    public void setBlockingReasons(String blockingReasons) { this.blockingReasons = blockingReasons; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
