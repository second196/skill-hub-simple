package com.km.skillhub.token.model;

import java.time.OffsetDateTime;
import java.util.List;

public class ApiTokenCreateResponse {
    private final Long id;
    private final String name;
    private final String token;
    private final String tokenPrefix;
    private final List<String> scopes;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime expiresAt;

    public ApiTokenCreateResponse(Long id, String name, String token, String tokenPrefix,
                                  List<String> scopes, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        this.id = id;
        this.name = name;
        this.token = token;
        this.tokenPrefix = tokenPrefix;
        this.scopes = scopes;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getToken() { return token; }
    public String getTokenPrefix() { return tokenPrefix; }
    public List<String> getScopes() { return scopes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
