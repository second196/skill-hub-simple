package com.km.skillhub.integration.runtime;

public interface RuntimeInstaller {
    boolean supports(String runtimeKey, String runtimeVersion);
    void accept(InstallationCommand command);
}
