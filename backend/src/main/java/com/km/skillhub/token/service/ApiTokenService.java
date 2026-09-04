package com.km.skillhub.token.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.governance.model.entity.AccountEntity;
import com.km.skillhub.mapper.governance.AccountMapper;
import com.km.skillhub.token.mapper.ApiTokenMapper;
import com.km.skillhub.token.model.ApiTokenCreateRequest;
import com.km.skillhub.token.model.ApiTokenCreateResponse;
import com.km.skillhub.token.model.ApiTokenEntity;
import com.km.skillhub.token.model.ApiTokenSummary;
import com.km.skillhub.token.security.ApiTokenAuthentication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ApiTokenService {
    private static final Set<String> SUPPORTED_SCOPES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "skill:read", "skill:publish", "telemetry:write", "token:manage")));

    private final ApiTokenMapper apiTokenMapper;
    private final AccountMapper accountMapper;
    private final AuditQueryService auditQueryService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiTokenService(ApiTokenMapper apiTokenMapper, AccountMapper accountMapper,
                           AuditQueryService auditQueryService, ObjectMapper objectMapper) {
        this.apiTokenMapper = apiTokenMapper;
        this.accountMapper = accountMapper;
        this.auditQueryService = auditQueryService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ApiTokenCreateResponse create(String username, ApiTokenCreateRequest request) {
        AccountEntity account = requireEnabledAccount(username);
        List<String> scopes = validateRequest(request);
        String token = generateToken();
        ApiTokenEntity entity = new ApiTokenEntity();
        entity.setPrincipalId(account.getId());
        entity.setName(request.getName().trim());
        entity.setTokenPrefix(prefixOf(token));
        entity.setTokenHash(hashOf(token));
        entity.setScopeJson(toJson(scopes));
        entity.setExpiresAt(request.getExpiresAt());
        entity.setCreatedAt(OffsetDateTime.now());
        try {
            apiTokenMapper.insert(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("同一账户下的访问令牌名称不能重复");
        }
        auditQueryService.record(username, "API_TOKEN_CREATED", "API_TOKEN", String.valueOf(entity.getId()),
                "创建访问令牌", "{}", tokenMetadata(entity), null, null, null);
        ApiTokenEntity saved = apiTokenMapper.findById(entity.getId());
        return new ApiTokenCreateResponse(saved.getId(), saved.getName(), token, saved.getTokenPrefix(),
                scopesOf(saved), saved.getCreatedAt(), saved.getExpiresAt());
    }

    public List<ApiTokenSummary> list(String username) {
        AccountEntity account = requireEnabledAccount(username);
        List<ApiTokenEntity> entities = apiTokenMapper.findByPrincipalId(account.getId());
        List<ApiTokenSummary> result = new ArrayList<ApiTokenSummary>();
        for (ApiTokenEntity entity : entities) {
            result.add(toSummary(entity));
        }
        return result;
    }

    @Transactional
    public ApiTokenSummary updateExpiration(String username, Long id, OffsetDateTime expiresAt) {
        AccountEntity account = requireEnabledAccount(username);
        validateExpiration(expiresAt);
        ApiTokenEntity before = requireOwnedToken(account.getId(), id);
        if (apiTokenMapper.updateExpiration(id, account.getId(), expiresAt) != 1) {
            throw new IllegalArgumentException("访问令牌已撤销或不存在");
        }
        ApiTokenEntity after = apiTokenMapper.findById(id);
        auditQueryService.record(username, "API_TOKEN_EXPIRATION_UPDATED", "API_TOKEN", String.valueOf(id),
                "更新访问令牌过期时间", tokenMetadata(before), tokenMetadata(after), null, null, null);
        return toSummary(after);
    }

    @Transactional
    public void revoke(String username, Long id) {
        AccountEntity account = requireEnabledAccount(username);
        ApiTokenEntity before = requireOwnedToken(account.getId(), id);
        if (before.getRevokedAt() != null) {
            throw new IllegalArgumentException("访问令牌已撤销");
        }
        if (apiTokenMapper.revoke(id, account.getId(), OffsetDateTime.now()) != 1) {
            throw new IllegalArgumentException("访问令牌已撤销或不存在");
        }
        ApiTokenEntity after = apiTokenMapper.findById(id);
        auditQueryService.record(username, "API_TOKEN_REVOKED", "API_TOKEN", String.valueOf(id),
                "撤销访问令牌", tokenMetadata(before), tokenMetadata(after), null, null, null);
    }

    public ApiTokenAuthentication authenticate(String rawToken) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            return null;
        }
        ApiTokenEntity entity = apiTokenMapper.findByHash(hashOf(rawToken));
        if (entity == null || entity.getRevokedAt() != null
                || (entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(OffsetDateTime.now()))) {
            return null;
        }
        AccountEntity account = accountMapper.findById(entity.getPrincipalId());
        if (account == null || !account.isEnabled()) {
            return null;
        }
        List<SimpleGrantedAuthority> authorities = new ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        for (String scope : scopesOf(entity)) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        apiTokenMapper.updateLastUsedAt(entity.getId(), OffsetDateTime.now());
        return new ApiTokenAuthentication(account.getUsername(), entity.getId(), authorities);
    }

    public boolean supportsScope(ApiTokenAuthentication authentication, String scope) {
        return authentication != null && authentication.getAuthorities().contains(
                new SimpleGrantedAuthority("SCOPE_" + scope));
    }

    private AccountEntity requireEnabledAccount(String username) {
        AccountEntity account = accountMapper.findByUsername(username);
        if (account == null || !account.isEnabled()) {
            throw new IllegalArgumentException("账户不存在或已停用");
        }
        return account;
    }

    private ApiTokenEntity requireOwnedToken(Long principalId, Long id) {
        if (id == null) {
            throw new IllegalArgumentException("访问令牌标识不能为空");
        }
        ApiTokenEntity entity = apiTokenMapper.findById(id);
        if (entity == null || !principalId.equals(entity.getPrincipalId())) {
            throw new IllegalArgumentException("访问令牌不存在或无权访问");
        }
        return entity;
    }

    private List<String> validateRequest(ApiTokenCreateRequest request) {
        if (request == null || request.getName() == null || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("访问令牌名称不能为空");
        }
        if (request.getName().trim().length() > 64) {
            throw new IllegalArgumentException("访问令牌名称不能超过 64 个字符");
        }
        validateExpiration(request.getExpiresAt());
        if (request.getScopes() == null || request.getScopes().isEmpty()) {
            throw new IllegalArgumentException("至少选择一个访问令牌作用域");
        }
        LinkedHashSet<String> uniqueScopes = new LinkedHashSet<String>();
        for (String scope : request.getScopes()) {
            if (scope == null || !SUPPORTED_SCOPES.contains(scope)) {
                throw new IllegalArgumentException("访问令牌作用域不受支持");
            }
            uniqueScopes.add(scope);
        }
        if (uniqueScopes.size() != request.getScopes().size()) {
            throw new IllegalArgumentException("访问令牌作用域不能重复");
        }
        return new ArrayList<String>(uniqueScopes);
    }

    private void validateExpiration(OffsetDateTime expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException("过期时间必须晚于当前时间");
        }
    }

    private ApiTokenSummary toSummary(ApiTokenEntity entity) {
        String status = entity.getRevokedAt() != null ? "REVOKED"
                : entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(OffsetDateTime.now())
                ? "EXPIRED" : "ACTIVE";
        return new ApiTokenSummary(entity.getId(), entity.getName(), entity.getTokenPrefix(), scopesOf(entity),
                entity.getCreatedAt(), entity.getExpiresAt(), entity.getLastUsedAt(), entity.getRevokedAt(), status);
    }

    private List<String> scopesOf(ApiTokenEntity entity) {
        if (entity.getScopeJson() == null || entity.getScopeJson().trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(entity.getScopeJson(), new TypeReference<List<String>>() { });
        } catch (Exception exception) {
            throw new IllegalArgumentException("访问令牌作用域数据无效");
        }
    }

    private String toJson(List<String> scopes) {
        try {
            return objectMapper.writeValueAsString(scopes);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("访问令牌作用域数据无效");
        }
    }

    private String tokenMetadata(ApiTokenEntity entity) {
        return "{\"name\":\"" + escape(entity.getName()) + "\",\"tokenPrefix\":\""
                + escape(entity.getTokenPrefix()) + "\",\"scopes\":" + entity.getScopeJson()
                + ",\"expiresAt\":" + (entity.getExpiresAt() == null ? "null" : "\"" + entity.getExpiresAt() + "\"")
                + ",\"revokedAt\":" + (entity.getRevokedAt() == null ? "null" : "\"" + entity.getRevokedAt() + "\"") + "}";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "sk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String prefixOf(String token) {
        return token.substring(0, Math.min(12, token.length()));
    }

    private String hashOf(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
