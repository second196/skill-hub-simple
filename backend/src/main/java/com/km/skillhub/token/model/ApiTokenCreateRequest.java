package com.km.skillhub.token.model;

import java.time.OffsetDateTime;
import java.util.List;

public class ApiTokenCreateRequest {
    private String name;
    private List<String> scopes;
    private OffsetDateTime expiresAt;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
}
