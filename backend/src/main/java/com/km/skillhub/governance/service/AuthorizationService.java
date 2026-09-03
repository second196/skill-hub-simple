package com.km.skillhub.governance.service;

import com.km.skillhub.governance.mapper.AuthorizationMapper;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {
    private final AuthorizationMapper authorizationMapper;
    public AuthorizationService(AuthorizationMapper authorizationMapper) { this.authorizationMapper = authorizationMapper; }
    public void requireRole(String username, Long scopeId, String roleKey) {
        if (username == null || scopeId == null || roleKey == null || !authorizationMapper.hasRole(username, scopeId, roleKey)) {
            throw new org.springframework.security.access.AccessDeniedException("Permission denied");
        }
    }

    public void requireAnyRole(String username, String roleKey) {
        if (username == null || roleKey == null || !authorizationMapper.hasAnyRole(username, roleKey)) {
            throw new org.springframework.security.access.AccessDeniedException("Permission denied");
        }
    }

    public void requireOneOfRoles(String username, Long scopeId, String... roleKeys) {
        if (username == null || scopeId == null || roleKeys == null) {
            throw new org.springframework.security.access.AccessDeniedException("Permission denied");
        }
        for (String roleKey : roleKeys) {
            if (authorizationMapper.hasRole(username, scopeId, roleKey)) return;
        }
        throw new org.springframework.security.access.AccessDeniedException("Permission denied");
    }
}
