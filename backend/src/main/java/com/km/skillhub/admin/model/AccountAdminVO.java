package com.km.skillhub.admin.model;

public class AccountAdminVO {
    private Long id;
    private String username;
    private boolean enabled;

    public AccountAdminVO() { }
    public AccountAdminVO(Long id, String username, boolean enabled) {
        this.id = id; this.username = username; this.enabled = enabled;
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
