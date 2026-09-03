package com.km.skillhub.installation.model.dto;

public class RevocationRequest {
    private String versionDigest;
    private String scopeType;
    private Long scopeId;
    private String reason;

    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String value) { versionDigest = value; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String value) { scopeType = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
