package com.km.skillhub.installation.service;

import com.km.skillhub.installation.model.InstallationState;
import org.springframework.stereotype.Service;

@Service
public class InstallationEligibilityPolicy {
    public String overallState(String skillState, String trackerState, String healthStatus) {
        if ("READY".equals(skillState) && "READY".equals(trackerState) && "HEALTHY".equals(healthStatus)) {
            return InstallationState.SUCCEEDED.name();
        }
        if ("READY".equals(skillState) && ("FAILED".equals(trackerState) || "NOT_INSTALLED".equals(trackerState))) {
            return InstallationState.INCOMPLETE.name();
        }
        if ("FAILED".equals(skillState) || "FAILED".equals(trackerState)
                || "UNHEALTHY".equals(healthStatus)) {
            return InstallationState.FAILED.name();
        }
        return InstallationState.VERIFYING.name();
    }

    public boolean canEnable(String skillState, String trackerState, String healthStatus) {
        return "SUCCEEDED".equals(overallState(skillState, trackerState, healthStatus));
    }
}
