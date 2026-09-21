package com.km.skillhub.skill;

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

    private final String code;

    public SkillApiException(String message, String code) {
        super(message);
        this.code = code == null ? CODE_VALIDATION_ERROR : code;
    }

    public String getCode() {
        return code;
    }
}
