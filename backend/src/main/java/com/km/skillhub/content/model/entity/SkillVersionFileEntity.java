package com.km.skillhub.content.model.entity;

public class SkillVersionFileEntity {
    private Long id;
    private Long versionId;
    private String path;
    private boolean required;
    private String readStatus;
    private String contentDigest;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public String getReadStatus() { return readStatus; }
    public void setReadStatus(String readStatus) { this.readStatus = readStatus; }
    public String getContentDigest() { return contentDigest; }
    public void setContentDigest(String contentDigest) { this.contentDigest = contentDigest; }
}
