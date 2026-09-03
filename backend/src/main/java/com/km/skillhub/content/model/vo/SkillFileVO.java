package com.km.skillhub.content.model.vo;

public class SkillFileVO {
    private final String path;
    private final boolean required;
    private final String readStatus;
    private final String contentDigest;

    public SkillFileVO(String path, boolean required, String readStatus, String contentDigest) {
        this.path = path;
        this.required = required;
        this.readStatus = readStatus;
        this.contentDigest = contentDigest;
    }

    public String getPath() { return path; }
    public boolean isRequired() { return required; }
    public String getReadStatus() { return readStatus; }
    public String getContentDigest() { return contentDigest; }
}
