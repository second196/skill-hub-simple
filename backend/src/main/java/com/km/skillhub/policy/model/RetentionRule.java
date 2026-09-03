package com.km.skillhub.policy.model;

public class RetentionRule {
    private String dataClass;
    private String retentionType;
    private Long retentionSeconds;

    public RetentionRule() { }
    public RetentionRule(String dataClass, String retentionType, Long retentionSeconds) {
        this.dataClass = dataClass;
        this.retentionType = retentionType;
        this.retentionSeconds = retentionSeconds;
    }
    public String getDataClass() { return dataClass; }
    public void setDataClass(String dataClass) { this.dataClass = dataClass; }
    public String getRetentionType() { return retentionType; }
    public void setRetentionType(String retentionType) { this.retentionType = retentionType; }
    public Long getRetentionSeconds() { return retentionSeconds; }
    public void setRetentionSeconds(Long retentionSeconds) { this.retentionSeconds = retentionSeconds; }
}
