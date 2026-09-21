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
    public static final String CODE_VERSION_DIGEST_CONFLICT = "VERSION_DIGEST_CONFLICT";
    /** Content changed but SemVer not increased — aligned with skillhub-cli. */
    public static final String CODE_VERSION_BUMP_REQUIRED = "VERSION_BUMP_REQUIRED";
    /** @deprecated use {@link #CODE_VERSION_BUMP_REQUIRED} */
    public static final String CODE_VERSION_REGRESSION = "VERSION_REGRESSION";
    public static final String CODE_VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String CODE_SKILL_NOT_FOUND = "SKILL_NOT_FOUND";
    public static final String CODE_VERSION_NOT_FOUND = "VERSION_NOT_FOUND";

    public static final String VERSION_REQUIRED_HINT =
            "version 必须为语义化版本（如 1.0.0）；平台/CLI 不会自动创建版本号，也不会默认 0.0.0。复合包不要创建根 SKILL.md，请在包根 package.json 声明 version，或 CLI 使用 --skill-version（全局 --version 是 CLI 自身版本开关）。";

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

    /** Actionable example for missing/invalid version. Composite packages: package.json only — do not invent root SKILL.md. */
    public static Map<String, Object> versionRequiredDetails(String name, String description) {
        Map<String, Object> packageJson = new LinkedHashMap<String, Object>();
        packageJson.put("name", name == null || name.trim().isEmpty() ? "my-composite-skill" : name.trim());
        packageJson.put("description", description == null || description.trim().isEmpty()
                ? "复合Skill包说明" : description.trim());
        packageJson.put("version", "1.0.0");
        Map<String, Object> example = new LinkedHashMap<String, Object>();
        example.put("packageJson", packageJson);
        example.put("cli", "skillhub upload <path> --skill-version 1.0.0");
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("hint", VERSION_REQUIRED_HINT);
        details.put("example", example);
        return details;
    }
}

