package com.km.skillhub.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SemVer helpers for skill version_label comparison and package version_digest.
 * version_digest = SHA-256 of normalized package lines: path\nsha256(bytes)\n (posix path sort).
 */
public final class SkillVersions {
    /**
     * Align with CLI SemVer: major.minor.patch with optional prerelease / build metadata.
     * Leading zeros on numeric identifiers are rejected (except single 0).
     */
    public static final String SEMVER_REGEX =
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$";

    private SkillVersions() {
    }

    public static boolean isSemVer(String value) {
        return normalizeVersionLabel(value) != null && normalizeVersionLabel(value).matches(SEMVER_REGEX);
    }

    /** Strip optional leading v/V and trim; return null if empty after normalize. */
    public static String normalizeVersionLabel(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V')) {
            String rest = v.substring(1);
            if (!rest.isEmpty() && Character.isDigit(rest.charAt(0))) v = rest;
        }
        return v.isEmpty() ? null : v;
    }

    /**
     * Content-addressed version digest, aligned with the standardized package algorithm:
     * files sorted by posix path; each line {@code path\nsha256(bytes)\n}; digest = sha256(concat lines).
     */
    public static String computeVersionDigest(List<String> paths, List<String> fileDigests) {
        if (paths == null || fileDigests == null || paths.size() != fileDigests.size()) {
            throw new IllegalArgumentException("版本摘要输入无效");
        }
        List<int[]> order = new ArrayList<int[]>(paths.size());
        for (int i = 0; i < paths.size(); i++) order.add(new int[] { i });
        final List<String> pathList = paths;
        java.util.Collections.sort(order, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return pathList.get(a[0]).compareTo(pathList.get(b[0]));
            }
        });
        StringBuilder lines = new StringBuilder();
        for (int[] idx : order) {
            String path = pathList.get(idx[0]);
            if (path == null) path = "";
            path = path.replace('\\', '/');
            lines.append(path).append('\n').append(fileDigests.get(idx[0])).append('\n');
        }
        return SkillPackageParser.sha256(lines.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Compare two SemVer labels. Returns &lt;0 if a&lt;b, 0 if equal precedence, &gt;0 if a&gt;b.
     * Unparseable labels are treated as lower than any valid SemVer (and equal to each other).
     */
    public static int compareSemVer(String a, String b) {
        Parsed pa = parse(a);
        Parsed pb = parse(b);
        if (pa == null && pb == null) return 0;
        if (pa == null) return -1;
        if (pb == null) return 1;
        if (pa.major != pb.major) return pa.major < pb.major ? -1 : 1;
        if (pa.minor != pb.minor) return pa.minor < pb.minor ? -1 : 1;
        if (pa.patch != pb.patch) return pa.patch < pb.patch ? -1 : 1;
        // No prerelease ranks higher than any prerelease.
        if (pa.pre.isEmpty() && pb.pre.isEmpty()) return 0;
        if (pa.pre.isEmpty()) return 1;
        if (pb.pre.isEmpty()) return -1;
        String[] as = pa.pre.split("\\.");
        String[] bs = pb.pre.split("\\.");
        int n = Math.min(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            int c = comparePreIdentifier(as[i], bs[i]);
            if (c != 0) return c;
        }
        return Integer.compare(as.length, bs.length);
    }

    public static String normalizeDigest(String digest) {
        if (digest == null) return null;
        String trimmed = digest.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int comparePreIdentifier(String a, String b) {
        boolean an = a.matches("\\d+");
        boolean bn = b.matches("\\d+");
        if (an && bn) {
            long va = Long.parseLong(a);
            long vb = Long.parseLong(b);
            return va == vb ? 0 : (va < vb ? -1 : 1);
        }
        if (an) return -1;
        if (bn) return 1;
        return a.compareTo(b);
    }

    private static Parsed parse(String value) {
        if (value == null) return null;
        String v = normalizeVersionLabel(value);
        if (v == null || !v.matches(SEMVER_REGEX)) return null;
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        String pre = "";
        int dash = v.indexOf('-');
        if (dash >= 0) {
            pre = v.substring(dash + 1);
            v = v.substring(0, dash);
        }
        String[] parts = v.split("\\.");
        if (parts.length != 3) return null;
        try {
            return new Parsed(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]), pre);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static final class Parsed {
        final long major;
        final long minor;
        final long patch;
        final String pre;

        Parsed(long major, long minor, long patch, String pre) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.pre = pre == null ? "" : pre;
        }
    }
}
