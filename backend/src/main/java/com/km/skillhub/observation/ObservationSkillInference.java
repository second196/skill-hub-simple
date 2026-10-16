package com.km.skillhub.observation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-time inference of skill identity from raw observation payloads.
 *
 * <p>Adapted from kmplm SkillRawInference: pure derivation, never writes back to the
 * payload. Used by the read-time analysis (skill associations) and the dashboard
 * stats (skill call counting / reference-material exclusion).</p>
 */
public final class ObservationSkillInference {

    private static final String UPD_SLUG = "using-product-development";
    private static final String SUPERPOWERS_SLUG = "superpowers";

    private static final Pattern SKILL_DIR = Pattern.compile(
            "(?:\\.agents|\\.claude|\\.codex)[\\\\/]skills[\\\\/]([a-zA-Z0-9_-]+)[\\\\/]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILL_MD = Pattern.compile(
            "skills[\\\\/]([a-zA-Z0-9_-]+)[\\\\/]SKILL\\.md",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SLASH_COMMAND = Pattern.compile(
            "(?:^|[\\s`'\"(\\[>])([/\\$])([a-z][a-z0-9-]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SEMVER = Pattern.compile(
            "^(?:[vV])?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    private ObservationSkillInference() {
    }

    /** Inferred skill identity: platform slug (composite rolled up) + leaf slug + version. */
    public static final class SkillIdentity {
        public final String slug;
        public final String leafSlug;
        public final String versionLabel;

        public SkillIdentity(String slug, String leafSlug, String versionLabel) {
            this.slug = slug;
            this.leafSlug = leafSlug;
            this.versionLabel = versionLabel;
        }

        public String displayName() {
            return leafSlug != null && !leafSlug.isEmpty() ? leafSlug : slug;
        }
    }

    /**
     * @param type    observation step type (user/tool/skill/...)
     * @param payload raw payload, may be null
     * @return null when nothing suggests a skill invocation
     */
    public static SkillIdentity infer(String type, Map<String, Object> payload) {
        if (payload == null) payload = Collections.emptyMap();

        String toolName = text(payload, "name", "tool", "toolName");
        Map<String, Object> args = map(payload.get("args"));
        Map<String, Object> input = map(payload.get("input"));

        // 1) explicit skill tool
        if (toolName != null && "skill".equalsIgnoreCase(toolName)) {
            String named = firstNonBlank(
                    text(args, "skill", "name", "slug"),
                    text(input, "skill", "name", "slug"),
                    text(payload, "skill", "slug"));
            if (named != null) {
                return identity(normalizeSlug(named), versionFrom(payload, args));
            }
        }

        // 2) path evidence in args / path / document_paths / result text
        String corpus = joinText(payload.get("path"), payload.get("document_paths"),
                args == null ? null : args.get("cmd"),
                args == null ? null : args.get("command"),
                args == null ? null : args.get("path"),
                args == null ? null : args.get("file"),
                args == null ? null : args.get("skill"),
                input == null ? null : input.get("cmd"),
                input == null ? null : input.get("command"),
                input == null ? null : input.get("path"),
                payload.get("result"), payload.get("text"), payload.get("content"));
        String fromPath = firstSlugFromPaths(corpus);
        if (fromPath != null) {
            return identity(fromPath, versionFrom(payload, args));
        }

        // 2b) known skill identifiers inside the text
        String named = firstKnownSlugInText(corpus);
        if (named != null) {
            return identity(named, versionFrom(payload, args));
        }

        // 3) slash command in user/system text
        if ("user".equals(type) || "system".equals(type)) {
            String textValue = text(payload, "text", "content", "message");
            if (textValue != null) {
                Matcher m = SLASH_COMMAND.matcher(textValue);
                while (m.find()) {
                    String slug = normalizeSlug(m.group(2));
                    if (isKnownSkillToken(slug)) {
                        return identity(slug, null);
                    }
                }
            }
        }

        // 4) legacy payload skill fields
        String legacy = firstNonBlank(
                text(payload, "skill_slug", "skillSlug", "skill_name", "skillName"));
        if (legacy != null) {
            return identity(normalizeSlug(legacy), versionFrom(payload, args));
        }
        return null;
    }

    /** Composite children roll up to their platform parent slug. */
    public static String toPlatformSlug(String slug) {
        String normalized = normalizeSlug(slug);
        if (normalized == null) return null;
        if (normalized.startsWith("sop-")) return UPD_SLUG;
        if ("using-superpowers".equals(normalized)) return SUPERPOWERS_SLUG;
        return normalized;
    }

    public static String normalizeVersion(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        Matcher m = SEMVER.matcher(cleaned);
        return m.matches() ? cleaned.replaceFirst("^[vV]", "") : null;
    }

    /**
     * True when the step only reads reference material (not a skill invocation):
     * system skill instruction lists, or docs under a skills directory's references
     * or docs folders that are not the SKILL.md itself.
     */
    public static boolean isReferenceMaterial(String type, Map<String, Object> payload) {
        if (payload == null) payload = Collections.emptyMap();
        if ("system".equalsIgnoreCase(type)) {
            String text = text(payload, "text", "content", "message");
            if (text != null) {
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.contains("skills_instructions") || lower.contains("## skills")) {
                    return true;
                }
            }
        }
        String corpus = joinText(
                payload.get("path"), payload.get("document_paths"),
                payload.get("args"), payload.get("input"),
                payload.get("result"));
        if (corpus == null || corpus.isEmpty()) return false;
        String lower = corpus.toLowerCase(Locale.ROOT);
        boolean hasSkillPath = lower.contains("skills/");
        boolean isSkillMd = lower.contains("skill.md");
        boolean hasReferences = lower.contains("/references/")
                || lower.contains("\\references\\")
                || lower.contains("/docs/")
                || lower.contains("\\docs\\");
        return hasSkillPath && hasReferences && !isSkillMd;
    }

    private static SkillIdentity identity(String rawSlug, String rawVersion) {
        String leaf = normalizeSlug(rawSlug);
        String platform = toPlatformSlug(rawSlug);
        if (platform == null && leaf == null) return null;
        return new SkillIdentity(platform != null ? platform : leaf, leaf, normalizeVersion(rawVersion));
    }

    private static String firstSlugFromPaths(String corpus) {
        if (corpus == null || corpus.isEmpty()) return null;
        String leaf = null;
        String first = null;
        Matcher md = SKILL_MD.matcher(corpus);
        while (md.find()) {
            String slug = normalizeSlug(md.group(1));
            if (slug == null) continue;
            if (first == null) first = slug;
            leaf = slug;
        }
        if (leaf == null) {
            Matcher dir = SKILL_DIR.matcher(corpus);
            while (dir.find()) {
                String slug = normalizeSlug(dir.group(1));
                if (slug == null) continue;
                if (first == null) first = slug;
                leaf = slug;
            }
        }
        return leaf != null ? leaf : first;
    }

    private static boolean isKnownSkillToken(String slug) {
        if (slug == null) return false;
        return slug.startsWith("sop-")
                || UPD_SLUG.equals(slug) || SUPERPOWERS_SLUG.equals(slug)
                || "using-superpowers".equals(slug);
    }

    private static String firstKnownSlugInText(String corpus) {
        if (corpus == null || corpus.isEmpty()) return null;
        String lower = corpus.toLowerCase(Locale.ROOT);
        String[] candidates = { UPD_SLUG, SUPERPOWERS_SLUG, "using-superpowers", "ui-ux-pro-max" };
        for (String candidate : candidates) {
            if (lower.contains(candidate)) return toPlatformSlug(candidate);
        }
        Matcher m = Pattern.compile("\\bsop-[a-z0-9-]+\\b").matcher(lower);
        if (m.find()) {
            return toPlatformSlug(m.group());
        }
        return null;
    }

    private static String normalizeSlug(String raw) {
        if (raw == null) return null;
        String slug = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? null : slug;
    }

    private static String versionFrom(Map<String, Object> payload, Map<String, Object> args) {
        return firstNonBlank(
                text(payload, "skill_version_label", "skillVersionLabel", "version"),
                text(args, "skill_version_label", "skillVersionLabel", "version"));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private static String text(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return (String) value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map) return (Map<String, Object>) value;
        return null;
    }

    private static String joinText(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            if (part == null) continue;
            if (part instanceof Map) {
                for (Object v : ((Map<?, ?>) part).values()) appendValue(sb, v);
            } else {
                appendValue(sb, part);
            }
        }
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) return;
        sb.append(' ').append(value);
    }
}
