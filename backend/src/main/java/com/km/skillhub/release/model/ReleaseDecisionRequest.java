package com.km.skillhub.release.model;

import java.util.List;

public class ReleaseDecisionRequest {
    private String versionDigest;
    private String scopeType;
    private Long scopeId;
    private String releaseMode;
    private String requestId;
    private List<Long> evidenceIds;
    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String versionDigest) { this.versionDigest = versionDigest; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public String getReleaseMode() { return releaseMode; }
    public void setReleaseMode(String releaseMode) { this.releaseMode = releaseMode; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public List<Long> getEvidenceIds() { return evidenceIds; }
    public void setEvidenceIds(List<Long> evidenceIds) { this.evidenceIds = evidenceIds; }
}
