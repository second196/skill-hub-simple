package com.km.skillhub.installation.model.entity;

import java.time.OffsetDateTime;

public class TrackerBindingEntity {
    private Long id;
    private Long installationInstanceId;
    private String trackerKey;
    private String trackerVersion;
    private String configurationDigest;
    private String installationState;
    private String healthState;
    private OffsetDateTime lastHealthAt;
    private String lastErrorCode;
    private String lastErrorReason;
    private String createdBy;
    private String updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long rowVersion;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getInstallationInstanceId() { return installationInstanceId; }
    public void setInstallationInstanceId(Long value) { installationInstanceId = value; }
    public String getTrackerKey() { return trackerKey; }
    public void setTrackerKey(String value) { trackerKey = value; }
    public String getTrackerVersion() { return trackerVersion; }
    public void setTrackerVersion(String value) { trackerVersion = value; }
    public String getConfigurationDigest() { return configurationDigest; }
    public void setConfigurationDigest(String value) { configurationDigest = value; }
    public String getInstallationState() { return installationState; }
    public void setInstallationState(String value) { installationState = value; }
    public String getHealthState() { return healthState; }
    public void setHealthState(String value) { healthState = value; }
    public OffsetDateTime getLastHealthAt() { return lastHealthAt; }
    public void setLastHealthAt(OffsetDateTime value) { lastHealthAt = value; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String value) { lastErrorCode = value; }
    public String getLastErrorReason() { return lastErrorReason; }
    public void setLastErrorReason(String value) { lastErrorReason = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String value) { updatedBy = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long value) { rowVersion = value; }
}
