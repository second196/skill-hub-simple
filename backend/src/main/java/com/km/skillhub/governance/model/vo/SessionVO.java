package com.km.skillhub.governance.model.vo;

public class SessionVO {

    private final boolean authenticated;
    private final String username;

    public SessionVO(boolean authenticated, String username) {
        this.authenticated = authenticated;
        this.username = username;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getUsername() {
        return username;
    }
}
