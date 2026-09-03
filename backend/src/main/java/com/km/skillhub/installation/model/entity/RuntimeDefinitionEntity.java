package com.km.skillhub.installation.model.entity;

public class RuntimeDefinitionEntity {
    private Long id;
    private String runtimeKey;
    private String runtimeVersion;
    private String status;
    private String capabilities;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public void setRuntimeVersion(String value) { runtimeVersion = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String value) { capabilities = value; }
}
