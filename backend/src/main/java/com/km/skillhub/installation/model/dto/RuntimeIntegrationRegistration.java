package com.km.skillhub.installation.model.dto;

public class RuntimeIntegrationRegistration {
    private Long scopeId;
    private String runtimeKey;
    private String runtimeVersion;
    private String targetKey;
    private String adapterVersion;
    private String configurationDigest;
    private String installationState;
    private String healthStatus;
    private String failureStage;
    private String errorCode;
    private String errorReason;

    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public void setRuntimeVersion(String value) { runtimeVersion = value; }
    public String getTargetKey() { return targetKey; }
    public void setTargetKey(String value) { targetKey = value; }
    public String getAdapterVersion() { return adapterVersion; }
    public void setAdapterVersion(String value) { adapterVersion = value; }
    public String getConfigurationDigest() { return configurationDigest; }
    public void setConfigurationDigest(String value) { configurationDigest = value; }
    public String getInstallationState() { return installationState; }
    public void setInstallationState(String value) { installationState = value; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String value) { healthStatus = value; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String value) { failureStage = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String value) { errorReason = value; }
}
