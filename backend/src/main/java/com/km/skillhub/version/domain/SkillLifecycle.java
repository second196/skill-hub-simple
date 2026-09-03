package com.km.skillhub.version.domain;

import java.util.EnumSet;
import java.util.Map;

public enum SkillLifecycle {
    DRAFT,
    CANDIDATE,
    PUBLISHED,
    OFFLINE,
    EMERGENCY_REVOKED,
    DEPRECATED;

    private static final Map<SkillLifecycle, EnumSet<SkillLifecycle>> TRANSITIONS =
            new java.util.EnumMap<SkillLifecycle, EnumSet<SkillLifecycle>>(SkillLifecycle.class);

    static {
        TRANSITIONS.put(DRAFT, EnumSet.of(CANDIDATE));
        TRANSITIONS.put(CANDIDATE, EnumSet.of(PUBLISHED, DRAFT, DEPRECATED));
        TRANSITIONS.put(PUBLISHED, EnumSet.of(OFFLINE, EMERGENCY_REVOKED));
        TRANSITIONS.put(OFFLINE, EnumSet.of(DEPRECATED, PUBLISHED));
        TRANSITIONS.put(EMERGENCY_REVOKED, EnumSet.of(DEPRECATED));
        TRANSITIONS.put(DEPRECATED, EnumSet.noneOf(SkillLifecycle.class));
    }

    public boolean canTransitionTo(SkillLifecycle target) {
        return target != null && TRANSITIONS.get(this).contains(target);
    }
}
