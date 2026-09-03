package com.km.skillhub.namespace.model.entity;

public class SkillNamespaceEntity {
    private Long id;
    private String namespaceKey;
    private String displayName;
    private Long ownerScopeId;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNamespaceKey() { return namespaceKey; }
    public void setNamespaceKey(String namespaceKey) { this.namespaceKey = namespaceKey; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Long getOwnerScopeId() { return ownerScopeId; }
    public void setOwnerScopeId(Long ownerScopeId) { this.ownerScopeId = ownerScopeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
