package com.km.skillhub.integration.runtime;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class RuntimeInstallerRegistry {
    private final List<RuntimeInstaller> installers;

    public RuntimeInstallerRegistry(List<RuntimeInstaller> installers) {
        this.installers = installers == null ? Collections.<RuntimeInstaller>emptyList() : installers;
    }

    public RuntimeInstaller resolve(String runtimeKey, String runtimeVersion) {
        for (RuntimeInstaller installer : installers) {
            if (installer.supports(runtimeKey, runtimeVersion)) return installer;
        }
        throw new IllegalArgumentException("No runtime installer is available");
    }
}
