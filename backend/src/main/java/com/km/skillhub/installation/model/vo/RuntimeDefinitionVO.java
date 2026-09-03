package com.km.skillhub.installation.model.vo;

public class RuntimeDefinitionVO {
    private String runtimeKey;
    private String runtimeVersion;
    private String status;
    private String capabilities;

    public RuntimeDefinitionVO(String runtimeKey, String runtimeVersion, String status, String capabilities) {
        this.runtimeKey = runtimeKey; this.runtimeVersion = runtimeVersion;
        this.status = status; this.capabilities = capabilities;
    }
    public String getRuntimeKey() { return runtimeKey; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public String getStatus() { return status; }
    public String getCapabilities() { return capabilities; }
}
