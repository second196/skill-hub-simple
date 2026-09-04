package com.km.skillhub.asset.service;

public class SkillPackageConflictException extends RuntimeException {
    private final String code;

    public SkillPackageConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
