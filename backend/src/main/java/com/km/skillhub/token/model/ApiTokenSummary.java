package com.km.skillhub.token.model;

import java.time.OffsetDateTime;
import java.util.List;

public class ApiTokenSummary {
    private final Long id;
    private final String name;
    private final String tokenPrefix;
    private final List<String> scopes;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime expiresAt;
    private final OffsetDateTime lastUsedAt;
    private final OffsetDateTime revokedAt;
    private final String status;

    public ApiTokenSummary(Long id, String name, String tokenPrefix, List<String> scopes,
                           OffsetDateTime createdAt, OffsetDateTime expiresAt,
                           OffsetDateTime lastUsedAt, OffsetDateTime revokedAt, String status) {
        this.id = id;
        this.name = name;
        this.tokenPrefix = tokenPrefix;
        this.scopes = scopes;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.lastUsedAt = lastUsedAt;
        this.revokedAt = revokedAt;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getTokenPrefix() { return tokenPrefix; }
    public List<String> getScopes() { return scopes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public String getStatus() { return status; }
}
