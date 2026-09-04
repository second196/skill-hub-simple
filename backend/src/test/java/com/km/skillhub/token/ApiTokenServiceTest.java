package com.km.skillhub.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.governance.model.entity.AccountEntity;
import com.km.skillhub.mapper.governance.AccountMapper;
import com.km.skillhub.token.mapper.ApiTokenMapper;
import com.km.skillhub.token.model.ApiTokenCreateRequest;
import com.km.skillhub.token.model.ApiTokenCreateResponse;
import com.km.skillhub.token.model.ApiTokenEntity;
import com.km.skillhub.token.service.ApiTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiTokenServiceTest {
    private ApiTokenMapper apiTokenMapper;
    private AccountMapper accountMapper;
    private AuditQueryService auditQueryService;
    private ApiTokenService service;
    private AccountEntity account;

    @BeforeEach
    void setUp() {
        apiTokenMapper = mock(ApiTokenMapper.class);
        accountMapper = mock(AccountMapper.class);
        auditQueryService = mock(AuditQueryService.class);
        service = new ApiTokenService(apiTokenMapper, accountMapper, auditQueryService, new ObjectMapper());
        account = new AccountEntity();
        account.setId(7L);
        account.setUsername("admin");
        account.setEnabled(true);
        when(accountMapper.findByUsername("admin")).thenReturn(account);
    }

    @Test
    void createsOpaqueTokenAndOnlyPersistsDigest() {
        when(apiTokenMapper.insert(any(ApiTokenEntity.class))).thenAnswer(invocation -> {
            ApiTokenEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            return 1;
        });
        when(apiTokenMapper.findById(11L)).thenAnswer(invocation -> savedToken(11L));

        ApiTokenCreateRequest request = new ApiTokenCreateRequest();
        request.setName("自动化发布");
        request.setScopes(Arrays.asList("skill:read", "skill:publish"));
        request.setExpiresAt(OffsetDateTime.now().plusDays(30));

        ApiTokenCreateResponse response = service.create("admin", request);

        assertNotNull(response.getToken());
        assertTrue(response.getToken().startsWith("sk_"));
        assertEquals("sk_abc12345", response.getTokenPrefix());
        assertFalse(response.getToken().equals(response.getTokenPrefix()));
        assertEquals(Arrays.asList("skill:read", "skill:publish"), response.getScopes());
        ArgumentCaptor<ApiTokenEntity> captor = ArgumentCaptor.forClass(ApiTokenEntity.class);
        verify(apiTokenMapper).insert(captor.capture());
        ApiTokenEntity persisted = captor.getValue();
        assertEquals(64, persisted.getTokenHash().length());
        assertFalse(persisted.getTokenHash().equals(response.getToken()));
        assertFalse(persisted.getScopeJson().contains(response.getToken()));
        verify(auditQueryService).record("admin", "API_TOKEN_CREATED", "API_TOKEN", "11",
                "创建访问令牌", "{}", persistedMetadata(persisted), null, null, null);
    }

    @Test
    void rejectsDuplicateScopesAndPastExpiration() {
        ApiTokenCreateRequest duplicateScopes = new ApiTokenCreateRequest();
        duplicateScopes.setName("重复作用域");
        duplicateScopes.setScopes(Arrays.asList("skill:read", "skill:read"));
        assertThrows(IllegalArgumentException.class, () -> service.create("admin", duplicateScopes));

        ApiTokenCreateRequest expired = new ApiTokenCreateRequest();
        expired.setName("已过期");
        expired.setScopes(Arrays.asList("skill:read"));
        expired.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> service.create("admin", expired));
    }

    @Test
    void acceptsTelemetryWriteScope() {
        when(apiTokenMapper.insert(any(ApiTokenEntity.class))).thenAnswer(invocation -> {
            ApiTokenEntity entity = invocation.getArgument(0);
            entity.setId(14L);
            return 1;
        });
        ApiTokenEntity saved = savedToken(14L);
        saved.setScopeJson("[\"telemetry:write\"]");
        when(apiTokenMapper.findById(14L)).thenReturn(saved);
        ApiTokenCreateRequest request = new ApiTokenCreateRequest();
        request.setName("运行数据上报");
        request.setScopes(Arrays.asList("telemetry:write"));

        ApiTokenCreateResponse response = service.create("admin", request);

        assertEquals(Arrays.asList("telemetry:write"), response.getScopes());
    }

    @Test
    void rejectsExpiredAndRevokedTokens() {
        ApiTokenEntity expired = savedToken(12L);
        expired.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(apiTokenMapper.findByHash(any(String.class))).thenReturn(expired);
        assertNull(service.authenticate("sk_expired"));

        ApiTokenEntity revoked = savedToken(13L);
        revoked.setRevokedAt(OffsetDateTime.now().minusMinutes(1));
        when(apiTokenMapper.findByHash(any(String.class))).thenReturn(revoked);
        assertNull(service.authenticate("sk_revoked"));
    }

    private ApiTokenEntity savedToken(Long id) {
        ApiTokenEntity entity = new ApiTokenEntity();
        entity.setId(id);
        entity.setPrincipalId(7L);
        entity.setName("自动化发布");
        entity.setTokenPrefix("sk_abc12345");
        entity.setTokenHash(repeat("a", 64));
        entity.setScopeJson("[\"skill:read\",\"skill:publish\"]");
        entity.setCreatedAt(OffsetDateTime.now().minusDays(1));
        entity.setExpiresAt(OffsetDateTime.now().plusDays(30));
        return entity;
    }

    private String persistedMetadata(ApiTokenEntity entity) {
        return "{\"name\":\"" + entity.getName() + "\",\"tokenPrefix\":\""
                + entity.getTokenPrefix() + "\",\"scopes\":" + entity.getScopeJson()
                + ",\"expiresAt\":\"" + entity.getExpiresAt() + "\",\"revokedAt\":null}";
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
