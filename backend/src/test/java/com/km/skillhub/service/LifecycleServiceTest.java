package com.km.skillhub.service;

import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.version.domain.SkillLifecycle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleServiceTest {
    @Test
    void lifecycleAllowsOnlyTheConfirmedTransitions() {
        assertTrue(SkillLifecycle.DRAFT.canTransitionTo(SkillLifecycle.CANDIDATE));
        assertTrue(SkillLifecycle.CANDIDATE.canTransitionTo(SkillLifecycle.PUBLISHED));
        assertTrue(SkillLifecycle.PUBLISHED.canTransitionTo(SkillLifecycle.EMERGENCY_REVOKED));
        assertFalse(SkillLifecycle.PUBLISHED.canTransitionTo(SkillLifecycle.CANDIDATE));
        assertFalse(SkillLifecycle.DEPRECATED.canTransitionTo(SkillLifecycle.PUBLISHED));
    }
}
