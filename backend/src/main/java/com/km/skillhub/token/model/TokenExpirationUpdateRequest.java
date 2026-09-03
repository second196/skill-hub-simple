package com.km.skillhub.token.model;

import java.time.OffsetDateTime;

public class TokenExpirationUpdateRequest {
    private OffsetDateTime expiresAt;

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
}
