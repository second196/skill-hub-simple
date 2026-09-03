package com.km.skillhub.installation.domain;

import com.km.skillhub.installation.model.InstallationState;

import java.util.EnumSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

public final class InstallationStateMachine {
    private static final Map<InstallationState, Set<InstallationState>> TRANSITIONS = transitions();

    private InstallationStateMachine() {
    }

    public static InstallationState transition(InstallationState current, InstallationState target) {
        if (current == null || target == null || !allowed(current, target)) {
            throw new IllegalArgumentException("Installation state transition is not allowed");
        }
        return target;
    }

    public static boolean allowed(InstallationState current, InstallationState target) {
        return current != null && target != null && current == target
                || TRANSITIONS.containsKey(current) && TRANSITIONS.get(current).contains(target);
    }

    private static Map<InstallationState, Set<InstallationState>> transitions() {
        Map<InstallationState, Set<InstallationState>> map = new HashMap<InstallationState, Set<InstallationState>>();
        map.put(InstallationState.REQUESTED, EnumSet.of(InstallationState.VALIDATING));
        map.put(InstallationState.VALIDATING, EnumSet.of(InstallationState.DOWNLOADING, InstallationState.FAILED));
        map.put(InstallationState.DOWNLOADING, EnumSet.of(InstallationState.INSTALLING_SKILL, InstallationState.FAILED));
        map.put(InstallationState.INSTALLING_SKILL, EnumSet.of(InstallationState.INSTALLING_TRACKER, InstallationState.FAILED));
        map.put(InstallationState.INSTALLING_TRACKER, EnumSet.of(InstallationState.CONFIGURING, InstallationState.FAILED));
        map.put(InstallationState.CONFIGURING, EnumSet.of(InstallationState.VERIFYING, InstallationState.FAILED));
        map.put(InstallationState.VERIFYING, EnumSet.of(InstallationState.SWITCHING, InstallationState.SUCCEEDED,
                InstallationState.INCOMPLETE, InstallationState.FAILED));
        map.put(InstallationState.SWITCHING, EnumSet.of(InstallationState.SUCCEEDED, InstallationState.FAILED,
                InstallationState.ROLLING_BACK));
        map.put(InstallationState.FAILED, EnumSet.of(InstallationState.ROLLING_BACK, InstallationState.UNAVAILABLE,
                InstallationState.REQUIRES_MANUAL, InstallationState.INCOMPLETE));
        map.put(InstallationState.ROLLING_BACK, EnumSet.of(InstallationState.ROLLED_BACK,
                InstallationState.REQUIRES_MANUAL));
        map.put(InstallationState.REVOKE_REQUESTED, EnumSet.of(InstallationState.REVOKING));
        map.put(InstallationState.REVOKING, EnumSet.of(InstallationState.REVOKED, InstallationState.PARTIAL_REVOKED,
                InstallationState.REQUIRES_MANUAL));
        return map;
    }
}
