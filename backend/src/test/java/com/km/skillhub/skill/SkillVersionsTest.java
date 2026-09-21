package com.km.skillhub.skill;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillVersionsTest {

    @Test
    void versionDigestMatchesSpecifiedNormalizedPackageAlgorithm() {
        // lines = path\nsha256(bytes)\n sorted by posix path; digest = sha256(concat)
        String pathA = "SKILL.md";
        String digestA = SkillPackageParser.sha256("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String pathB = "docs/readme.md";
        String digestB = SkillPackageParser.sha256("world".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String expected = SkillPackageParser.sha256(
                (pathA + "\n" + digestA + "\n" + pathB + "\n" + digestB + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Unsorted input must still produce the same digest
        String actual = SkillVersions.computeVersionDigest(
                Arrays.asList(pathB, pathA),
                Arrays.asList(digestB, digestA));
        assertEquals(expected, actual);
        assertEquals(64, actual.length());
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    void digestIgnoresNameAndVersionLabel() {
        String digest1 = repeat('a', 64);
        String digest2 = repeat('b', 64);
        assertEquals(
                SkillVersions.computeVersionDigest(Collections.singletonList("SKILL.md"), Collections.singletonList(digest1)),
                SkillVersions.computeVersionDigest(Collections.singletonList("SKILL.md"), Collections.singletonList(digest1)));
        assertFalse(SkillVersions.computeVersionDigest(Collections.singletonList("SKILL.md"), Collections.singletonList(digest1))
                .equals(SkillVersions.computeVersionDigest(Collections.singletonList("SKILL.md"), Collections.singletonList(digest2))));
    }

    @Test
    void semVerComparisonOrdersCoreAndPrerelease() {
        assertTrue(SkillVersions.compareSemVer("1.2.3", "1.2.2") > 0);
        assertTrue(SkillVersions.compareSemVer("1.2.3", "1.10.0") < 0);
        assertTrue(SkillVersions.compareSemVer("1.2.3", "1.2.3") == 0);
        assertTrue(SkillVersions.compareSemVer("1.2.3", "1.2.3-rc.1") > 0);
        assertTrue(SkillVersions.compareSemVer("1.2.3-rc.2", "1.2.3-rc.10") < 0);
        assertTrue(SkillVersions.compareSemVer("1.2.3+build.1", "1.2.3") == 0);
        assertTrue(SkillVersions.compareSemVer("01.2.3", "1.2.3") < 0); // leading zero is not SemVer
        assertTrue(SkillVersions.isSemVer("1.0.0"));
        assertTrue(SkillVersions.isSemVer("1.0.0-beta.1+exp.sha.5114f85"));
        assertFalse(SkillVersions.isSemVer(null));
        assertFalse(SkillVersions.isSemVer(""));
        assertFalse(SkillVersions.isSemVer("1.0"));
        assertTrue(SkillVersions.isSemVer("v1.0.0")); // leading v stripped
        assertEquals("1.0.0", SkillVersions.normalizeVersionLabel("v1.0.0"));
        assertFalse(SkillVersions.isSemVer("01.0.0"));
    }

    @Test
    void normalizeDigestTrimsAndLowercases() {
        assertEquals(null, SkillVersions.normalizeDigest(null));
        assertEquals(null, SkillVersions.normalizeDigest("  "));
        assertEquals("abc", SkillVersions.normalizeDigest("  ABC  "));
        List<String> paths = Collections.singletonList("a");
        assertEquals(64, SkillVersions.computeVersionDigest(paths, Collections.singletonList(repeat('0', 64))).length());
    }
}
