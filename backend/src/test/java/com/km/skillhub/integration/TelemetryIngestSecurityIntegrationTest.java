package com.km.skillhub.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class TelemetryIngestSecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void enforcesBearerScopeAndGovernanceScope() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String telemetryToken = createToken("遥测写入" + suffix, "telemetry:write");
        String readToken = createToken("遥测只读" + suffix, "skill:read");

        postTelemetry(telemetryToken, "allowed-" + suffix, payload(suffix, 1L))
                .andExpect(status().isOk());
        postTelemetry(readToken, "wrong-scope-" + suffix, payload(suffix, 1L))
                .andExpect(status().isForbidden());
        postTelemetry(telemetryToken, "cross-scope-" + suffix, payload(suffix, 999999L))
                .andExpect(status().isForbidden());
        postTelemetry("invalid", "invalid-token-" + suffix, payload(suffix, 1L))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/telemetry/otlp/v1/traces")
                        .with(user("admin"))
                        .with(csrf())
                        .header("X-Request-Id", "session-" + suffix)
                        .contentType(APPLICATION_JSON)
                        .content(payload(suffix, 1L)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenAfterOwningAccountIsDisabled() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String token = createTokenAs("user", "停用账号" + suffix, "telemetry:write");
        jdbcTemplate.update("UPDATE principal_account SET enabled = FALSE WHERE username = 'user'");
        try {
            postTelemetry(token, "disabled-" + suffix, payload(suffix, 1L))
                    .andExpect(status().isUnauthorized());
        } finally {
            jdbcTemplate.update("UPDATE principal_account SET enabled = TRUE WHERE username = 'user'");
        }
    }

    private org.springframework.test.web.servlet.ResultActions postTelemetry(
            String token, String requestId, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/telemetry/otlp/v1/traces")
                .header("Authorization", "Bearer " + token)
                .header("X-Request-Id", requestId)
                .contentType(APPLICATION_JSON)
                .content(payload));
    }

    private String createToken(String name, String scope) throws Exception {
        return createTokenAs("admin", name, scope);
    }

    private String createTokenAs(String username, String name, String scope) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tokens")
                        .with(user(username))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"scopes\":[\"" + scope + "\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private String payload(String suffix, long scopeId) {
        long start = Instant.now().toEpochMilli() * 1000000L;
        return "{\"resourceSpans\":[{\"resource\":{\"attributes\":["
                + attr("skillhub.scope.id", String.valueOf(scopeId), "intValue") + ","
                + attr("service.name", "codex-cli", "stringValue") + ","
                + attr("service.version", "0.1.0", "stringValue") + "]},"
                + "\"scopeSpans\":[{\"spans\":[{\"traceId\":\"" + suffix.substring(0, 32) + "\","
                + "\"spanId\":\"" + suffix.substring(0, 16) + "\",\"name\":\"agent run\","
                + "\"startTimeUnixNano\":\"" + start + "\",\"endTimeUnixNano\":\"" + (start + 1000000L) + "\","
                + "\"attributes\":[" + attr("session.id", "session-" + suffix, "stringValue") + ","
                + attr("event.id", "security-event-" + suffix, "stringValue") + "]}]}]}]}";
    }

    private String attr(String key, String value, String valueType) {
        return "{\"key\":\"" + key + "\",\"value\":{\"" + valueType + "\":\"" + value + "\"}}";
    }
}
