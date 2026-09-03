package com.km.skillhub.namespace.model.vo;

public class NamespaceMemberVO {
    private final Long principalId;
    private final String username;
    private final String roleKey;

    public NamespaceMemberVO(Long principalId, String username, String roleKey) {
        this.principalId = principalId;
        this.username = username;
        this.roleKey = roleKey;
    }

    public Long getPrincipalId() { return principalId; }
    public String getUsername() { return username; }
    public String getRoleKey() { return roleKey; }
}
