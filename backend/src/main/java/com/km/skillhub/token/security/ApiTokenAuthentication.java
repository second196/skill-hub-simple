package com.km.skillhub.token.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class ApiTokenAuthentication extends AbstractAuthenticationToken {
    private final String username;
    private final Long tokenId;

    public ApiTokenAuthentication(String username, Long tokenId,
                                   Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.username = username;
        this.tokenId = tokenId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() { return null; }

    @Override
    public Object getPrincipal() { return username; }

    @Override
    public String getName() { return username; }

    public Long getTokenId() { return tokenId; }
}
