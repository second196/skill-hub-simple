package com.km.skillhub.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RuntimeIntegrationIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTokens() {
        jdbcTemplate.update("DELETE FROM api_token");
    }

    @Test
    void registersAndReplaysRuntimeIntegrationIdempotently() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String telemetryToken = createToken("运行时接入" + suffix, "telemetry:write");
        String readToken = createToken("错误作用域" + suffix, "skill:read");
        String targetKey = digest(suffix, 'a');
        String configurationDigest = digest(suffix, 'b');
        String registration = "{\"scopeId\":1,\"runtimeKey\":\"codex-cli\","
                + "\"runtimeVersion\":\"initial\",\"targetKey\":\"" + targetKey + "\","
                + "\"adapterVersion\":\"0.1.0\",\"configurationDigest\":\""
                + configurationDigest + "\",\"installationState\":\"ACTIVE\","
                + "\"healthStatus\":\"HEALTHY\"}";

        MvcResult first = mockMvc.perform(post("/api/v1/runtime-integrations")
                        .header("Authorization", "Bearer " + telemetryToken)
                        .contentType(APPLICATION_JSON)
                        .content(registration))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrationId").isNotEmpty())
                .andExpect(jsonPath("$.runtimeKey").value("codex-cli"))
                .andReturn();
        String integrationId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("integrationId").asText();

        mockMvc.perform(post("/api/v1/runtime-integrations")
                        .header("Authorization", "Bearer " + telemetryToken)
                        .contentType(APPLICATION_JSON)
                        .content(registration))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrationId").value(integrationId));
        mockMvc.perform(post("/api/v1/runtime-integrations")
                        .header("Authorization", "Bearer " + readToken)
                        .contentType(APPLICATION_JSON)
                        .content(registration))
                .andExpect(status().isForbidden());

        String eventId = "runtime-event-" + suffix;
        String event = "{\"eventId\":\"" + eventId + "\",\"eventSequence\":1,"
                + "\"eventType\":\"CHECK_COMPLETED\",\"stage\":\"VERIFYING\","
                + "\"result\":\"SUCCEEDED\",\"installationState\":\"ACTIVE\","
                + "\"healthStatus\":\"HEALTHY\",\"occurredAt\":\"2026-09-03T06:00:00Z\"}";
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/runtime-integrations/{integrationId}/events", integrationId)
                            .header("Authorization", "Bearer " + telemetryToken)
                            .contentType(APPLICATION_JSON)
                            .content(event))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.integrationId").value(integrationId))
                    .andExpect(jsonPath("$.lastEventSequence").value(1));
        }

        mockMvc.perform(get("/api/v1/runtime-integrations/{integrationId}", integrationId)
                        .with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthStatus").value("HEALTHY"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM runtime_integration_instance WHERE integration_id = ?",
                Integer.class, integrationId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM runtime_integration_event WHERE event_id = ?",
                Integer.class, eventId));
    }

    @Test
    void rejectsCrossScopeRegistration() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String telemetryToken = createToken("跨范围接入" + suffix, "telemetry:write");

        mockMvc.perform(post("/api/v1/runtime-integrations")
                        .header("Authorization", "Bearer " + telemetryToken)
                        .contentType(APPLICATION_JSON)
                        .content(registration(999999L, suffix)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsTokenAfterOwningAccountIsDisabled() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String telemetryToken = createTokenAs("user", "停用账户接入" + suffix, "telemetry:write");
        jdbcTemplate.update("UPDATE principal_account SET enabled = FALSE WHERE username = 'user'");
        try {
            mockMvc.perform(post("/api/v1/runtime-integrations")
                            .header("Authorization", "Bearer " + telemetryToken)
                            .contentType(APPLICATION_JSON)
                            .content(registration(1L, suffix)))
                    .andExpect(status().isUnauthorized());
        } finally {
            jdbcTemplate.update("UPDATE principal_account SET enabled = TRUE WHERE username = 'user'");
        }
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
        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        return payload.get("token").asText();
    }

    private String registration(long scopeId, String suffix) {
        return "{\"scopeId\":" + scopeId + ",\"runtimeKey\":\"codex-cli\"," 
                + "\"runtimeVersion\":\"initial\",\"targetKey\":\"" + digest(suffix, 'a') + "\"," 
                + "\"adapterVersion\":\"0.1.0\",\"configurationDigest\":\"" + digest(suffix, 'b') + "\"," 
                + "\"installationState\":\"ACTIVE\",\"healthStatus\":\"HEALTHY\"}";
    }

    private String digest(String suffix, char fill) {
        String value = suffix + repeat(fill, 64);
        return value.substring(0, 64).toLowerCase();
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
