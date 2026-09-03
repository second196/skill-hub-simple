package com.km.skillhub.policy.model.entity;

import java.time.OffsetDateTime;

public class RetentionPolicyVersionEntity {
    private Long id; private String scopeType; private Long scopeId; private String dataClass;
    private String retentionType; private Long retentionSeconds; private String policyVersion;
    private OffsetDateTime effectiveAt; private String approvedBy; private String createdBy;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public String getDataClass() { return dataClass; }
    public void setDataClass(String dataClass) { this.dataClass = dataClass; }
    public String getRetentionType() { return retentionType; }
    public void setRetentionType(String retentionType) { this.retentionType = retentionType; }
    public Long getRetentionSeconds() { return retentionSeconds; }
    public void setRetentionSeconds(Long retentionSeconds) { this.retentionSeconds = retentionSeconds; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public OffsetDateTime getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(OffsetDateTime effectiveAt) { this.effectiveAt = effectiveAt; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
