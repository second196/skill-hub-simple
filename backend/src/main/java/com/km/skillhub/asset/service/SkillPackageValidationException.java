package com.km.skillhub.asset.service;

public class SkillPackageValidationException extends IllegalArgumentException {
    private final String code;

    public SkillPackageValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SkillPackageValidationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
