package com.km.skillhub.policy.model.entity;

import java.time.OffsetDateTime;

public class ReleasePolicyVersionEntity {
    private Long id;
    private String scopeType;
    private Long scopeId;
    private String policyVersion;
    private OffsetDateTime effectiveAt;
    private Integer minimumValidCases;
    private Double grayRatio;
    private Long observationWindowSeconds;
    private Integer minimumValidCalls;
    private String rollbackConditions;
    private String autoReleaseConditions;
    private String createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public OffsetDateTime getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(OffsetDateTime effectiveAt) { this.effectiveAt = effectiveAt; }
    public Integer getMinimumValidCases() { return minimumValidCases; }
    public void setMinimumValidCases(Integer minimumValidCases) { this.minimumValidCases = minimumValidCases; }
    public Double getGrayRatio() { return grayRatio; }
    public void setGrayRatio(Double grayRatio) { this.grayRatio = grayRatio; }
    public Long getObservationWindowSeconds() { return observationWindowSeconds; }
    public void setObservationWindowSeconds(Long observationWindowSeconds) { this.observationWindowSeconds = observationWindowSeconds; }
    public Integer getMinimumValidCalls() { return minimumValidCalls; }
    public void setMinimumValidCalls(Integer minimumValidCalls) { this.minimumValidCalls = minimumValidCalls; }
    public String getRollbackConditions() { return rollbackConditions; }
    public void setRollbackConditions(String rollbackConditions) { this.rollbackConditions = rollbackConditions; }
    public String getAutoReleaseConditions() { return autoReleaseConditions; }
    public void setAutoReleaseConditions(String autoReleaseConditions) { this.autoReleaseConditions = autoReleaseConditions; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
