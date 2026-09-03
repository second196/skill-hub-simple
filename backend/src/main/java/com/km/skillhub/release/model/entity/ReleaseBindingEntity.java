package com.km.skillhub.release.model.entity;

import java.time.OffsetDateTime;

public class ReleaseBindingEntity {
    private Long id;
    private Long assetId;
    private String versionDigest;
    private String scopeType;
    private Long scopeId;
    private String bindingState;
    private Boolean current;
    private String policyVersion;
    private Long decisionId;
    private OffsetDateTime effectiveAt;
    private OffsetDateTime releasedAt;
    private String createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String versionDigest) { this.versionDigest = versionDigest; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public String getBindingState() { return bindingState; }
    public void setBindingState(String bindingState) { this.bindingState = bindingState; }
    public Boolean getCurrent() { return current; }
    public void setCurrent(Boolean current) { this.current = current; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public Long getDecisionId() { return decisionId; }
    public void setDecisionId(Long decisionId) { this.decisionId = decisionId; }
    public OffsetDateTime getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(OffsetDateTime effectiveAt) { this.effectiveAt = effectiveAt; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
