package com.km.skillhub.skill;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillVersionsTest {

    @Test
    void semVerComparisonOrdersCoreAndPrerelease() {
        assertTrue(SkillVersions.compareSemVer("1.2.3", "1.2.2") > 0);
        assertTrue(SkillVersions.compareSemVer("1.2.3", "1.10.0") < 0);
        assertTrue(SkillVersions.compareSemVer("1.2.3", "1.2.3") == 0);
        assertTrue(SkillVersions.compareSemVer("1.2.3", "1.2.3-rc.1") > 0);
        assertTrue(SkillVersions.compareSemVer("1.2.3-rc.2", "1.2.3-rc.10") < 0);
        assertTrue(SkillVersions.compareSemVer("1.2.3+build.1", "1.2.3") == 0);
        assertTrue(SkillVersions.compareSemVer("01.2.3", "1.2.3") < 0);
        assertTrue(SkillVersions.isSemVer("1.0.0"));
        assertTrue(SkillVersions.isSemVer("1.0.0-beta.1+exp.sha.5114f85"));
        assertFalse(SkillVersions.isSemVer(null));
        assertFalse(SkillVersions.isSemVer(""));
        assertFalse(SkillVersions.isSemVer("1.0"));
        assertTrue(SkillVersions.isSemVer("v1.0.0"));
        assertEquals("1.0.0", SkillVersions.normalizeVersionLabel("v1.0.0"));
        assertFalse(SkillVersions.isSemVer("01.0.0"));
    }

    @Test
    void latestFormalVersionIgnoresPrereleaseAndInvalid() {
        assertEquals("1.0.1", SkillVersions.latestFormalVersion(Arrays.asList("1.0.0", "1.0.1", "2.0.0-rc.1", "not-a-version")));
        assertEquals(null, SkillVersions.latestFormalVersion(Collections.<String>emptyList()));
        assertEquals(null, SkillVersions.latestFormalVersion(Arrays.asList("2.0.0-rc.1")));
    }

    @Test
    void suggestNextVersionIncrementsPatch() {
        assertEquals("0.0.1", SkillVersions.suggestNextVersion(null));
        assertEquals("1.0.2", SkillVersions.suggestNextVersion("1.0.1"));
        assertEquals("1.2.4", SkillVersions.suggestNextVersion("v1.2.3"));
        assertEquals("0.0.1", SkillVersions.suggestNextVersion("2.0.0-rc.1"));
    }

    @Test
    void resolveCategoryPrefersPackageThenPlatformThenRequest() {
        assertEquals("研发", SkillRepository.resolveCategory("研发", "工具", "其他"));
        assertEquals("工具", SkillRepository.resolveCategory(null, "工具", "其他"));
        assertEquals("其他", SkillRepository.resolveCategory(null, null, "其他"));
        assertEquals("其他", SkillRepository.resolveCategory(null, null, null));
        assertEquals("工具", SkillRepository.resolveCategory("  工具  ", "研发", "其他"));
    }
}
