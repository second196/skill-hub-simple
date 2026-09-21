package com.km.skillhub.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured API error with a stable machine-readable code for clients (CLI/web).
 * Extends IllegalArgumentException so existing assertThrows and generic handlers stay compatible.
 */
public class SkillApiException extends IllegalArgumentException {
    public static final String CODE_VERSION_REQUIRED = "VERSION_REQUIRED";
    public static final String CODE_VERSION_INVALID = "VERSION_INVALID";
    /** Same skill + same SemVer label already on the platform (versions are immutable). */
    public static final String CODE_VERSION_EXISTS = "VERSION_EXISTS";
    /** Incoming SemVer is not strictly greater than the latest formal platform version. */
    public static final String CODE_VERSION_BUMP_REQUIRED = "VERSION_BUMP_REQUIRED";
    /** @deprecated use {@link #CODE_VERSION_BUMP_REQUIRED} */
    public static final String CODE_VERSION_REGRESSION = "VERSION_REGRESSION";
    public static final String CODE_VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String CODE_SKILL_NOT_FOUND = "SKILL_NOT_FOUND";
    public static final String CODE_VERSION_NOT_FOUND = "VERSION_NOT_FOUND";

    public static final String VERSION_REQUIRED_HINT =
            "version 必须写在 Skill 包内且为语义化版本（如 1.0.0）。"
                    + "单 Skill：根 SKILL.md frontmatter 的 version。"
                    + "复合包：包根 package.json 或 .codex-plugin/plugin.json 的 version（不要创建根 SKILL.md）。"
                    + "CLI 不会通过参数注入版本号，平台/CLI 也不会自动创建版本号或默认 0.0.0。"
                    + "请先补全包内元数据后再上传；若源目录只读，先复制到临时目录补全，再完整覆盖回源目录后上传。";

    private final String code;
    private final Map<String, Object> details;

    public SkillApiException(String message, String code) {
        this(message, code, null);
    }

    public SkillApiException(String message, String code, Map<String, Object> details) {
        super(message);
        this.code = code == null ? CODE_VALIDATION_ERROR : code;
        this.details = details == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(details));
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    /** Actionable example for missing/invalid version. Version must live inside the package. */
    public static Map<String, Object> versionRequiredDetails(String name, String description) {
        return versionRequiredDetails(name, description, null);
    }

    public static Map<String, Object> versionRequiredDetails(String name, String description, String packageKind) {
        boolean composite = packageKind == null || "composite".equalsIgnoreCase(packageKind);
        Map<String, Object> packageJson = new LinkedHashMap<String, Object>();
        packageJson.put("name", name == null || name.trim().isEmpty() ? "my-skill" : name.trim());
        packageJson.put("description", description == null || description.trim().isEmpty()
                ? "Skill说明" : description.trim());
        packageJson.put("version", "1.0.0");
        packageJson.put("category", "研发");
        Map<String, Object> example = new LinkedHashMap<String, Object>();
        if (composite) {
            example.put("packageJson", packageJson);
        } else {
            Map<String, Object> skillMd = new LinkedHashMap<String, Object>();
            skillMd.put("skillMd",
                    "---\n"
                            + "name: " + packageJson.get("name") + "\n"
                            + "description: " + packageJson.get("description") + "\n"
                            + "version: 1.0.0\n"
                            + "category: 研发\n"
                            + "---\n");
            example.putAll(skillMd);
        }
        example.put("cli", "skillhub upload <skill-dir> --json");
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("hint", VERSION_REQUIRED_HINT);
        details.put("example", example);
        details.put("packageKind", composite ? "composite" : "skill-md");
        return details;
    }

    /** Details for immutable-version rejection with an explicit next-version suggestion. */
    public static Map<String, Object> versionExistsDetails(String existingLabel, String maxFormal, String suggested) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("version", existingLabel);
        details.put("maxFormal", maxFormal);
        details.put("suggestedNextVersion", suggested);
        details.put("hint",
                "同一 Skill 的 version 不可覆盖。请修改包内 version（单 Skill：SKILL.md frontmatter；"
                        + "复合包：package.json）为大于平台当前版本的 SemVer 后重新上传。");
        return details;
    }
}
