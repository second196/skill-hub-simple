package com.km.skillhub.observation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationSkillSessionScopeTest {

    @Test
    void membershipRequiresPlatformPublishedVersion() {
        String sql = ObservationRepository.skillStepMembership("st", ObservationRepository.VersionFilter.of("all"));
        assertTrue(sql.contains("st.type='skill'"));
        assertTrue(sql.contains("st.skill_slug=?"));
        assertTrue(sql.contains("EXISTS (SELECT 1 FROM skill_version"));
        assertFalse(sql.contains("TRIM(st.skill_version_label)='1.0.0'"));
    }

    @Test
    void membershipAddsVersionFilterNarrowing() {
        String all = ObservationRepository.skillStepMembership("st", ObservationRepository.VersionFilter.of(null));
        String v101 = ObservationRepository.skillStepMembership("st", ObservationRepository.VersionFilter.of("1.0.1"));
        assertTrue(v101.contains("TRIM(st.skill_version_label)='1.0.1'"));
        assertFalse(all.contains("TRIM(st.skill_version_label)='1.0.1'"));
        assertTrue(v101.contains("st.type='skill'"));
    }

    @Test
    void sessionMembershipIsVersionAgnostic() {
        // 会话链路列表不得被指标页 Skill 版本筛选收窄
        String sessionScope = ObservationRepository.skillStepMembership("st", ObservationRepository.VersionFilter.ALL);
        assertFalse(sessionScope.contains("TRIM(st.skill_version_label)='1.0.1'"));
        assertTrue(sessionScope.contains("st.type='skill'"));
        assertTrue(sessionScope.contains("EXISTS (SELECT 1 FROM skill_version"));
    }

    @Test
    void publishedVersionMatchSupportsAlias() {
        String sql = ObservationRepository.publishedVersionMatch("mst");
        assertTrue(sql.contains("mst.skill_slug"));
        assertTrue(sql.contains("mst.skill_version_label"));
        assertFalse(sql.contains("=st.skill_version_label"));
    }

    @Test
    void versionFilterAcceptsLeadingV() {
        String sql = ObservationRepository.VersionFilter.of("v1.0.0").sql;
        assertTrue(sql.contains("'1.0.0'"));
        assertTrue(sql.contains("'v1.0.0'"));
    }
}
