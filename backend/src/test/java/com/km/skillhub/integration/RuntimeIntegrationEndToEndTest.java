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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RuntimeIntegrationEndToEndTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registersActualVersionAndProjectsFailureRecoveryWithoutDuplicateEvents() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String token = createToken("纵向接入" + suffix);
        String targetKey = digest(suffix, 'a');
        String registration = objectMapper.writeValueAsString(registration(targetKey, digest(suffix, 'b')));

        MvcResult registered = mockMvc.perform(post("/api/v1/runtime-integrations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(registration))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeVersion").value("0.151.0"))
                .andExpect(jsonPath("$.installationState").value("ACTION_REQUIRED"))
                .andExpect(jsonPath("$.healthStatus").value("DEGRADED"))
                .andReturn();
        String integrationId = objectMapper.readTree(registered.getResponse().getContentAsString())
                .get("integrationId").asText();

        postEvent(token, integrationId, event("action-" + suffix, 1,
                "ACTION_REQUIRED", "DEGRADED", "ACTION_REQUIRED", null));
        String failedEventId = "failed-" + suffix;
        String secretReason = "读取 C:\\Users\\admin\\.skillhub\\state.json 失败，Bearer sk_secret";
        postEvent(token, integrationId, event(failedEventId, 2,
                "FAILED", "UNHEALTHY", "FAILED", secretReason));
        postEvent(token, integrationId, event(failedEventId, 2,
                "FAILED", "UNHEALTHY", "FAILED", secretReason));
        postEvent(token, integrationId, event("recovered-" + suffix, 3,
                "ACTIVE", "HEALTHY", "SUCCEEDED", null));

        MvcResult listResult = mockMvc.perform(get("/api/v1/runtime-integrations")
                        .param("scopeId", "1")
                        .param("runtimeKey", "codex-cli")
                        .param("limit", "100")
                        .with(user("admin")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode item = findById(objectMapper.readTree(listResult.getResponse().getContentAsString()), integrationId);
        assertEquals("0.151.0", item.get("runtimeVersion").asText());
        assertEquals("ACTIVE", item.get("installationState").asText());
        assertEquals("HEALTHY", item.get("healthStatus").asText());
        assertEquals(3, item.get("lastEventSequence").asInt());
        assertFalse(item.toString().contains("sk_secret"));
        assertFalse(item.toString().contains("C:\\Users"));

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM runtime_integration_event WHERE event_id = ?",
                Integer.class, failedEventId));

        String unauthorizedUsername = "runtime-viewer-" + suffix;
        jdbcTemplate.update("INSERT INTO principal_account(username, password_hash, enabled) VALUES (?, ?, TRUE)",
                unauthorizedUsername, "test-only");
        try {
            mockMvc.perform(get("/api/v1/runtime-integrations/{integrationId}", integrationId)
                            .with(user(unauthorizedUsername)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/runtime-integrations")
                            .param("scopeId", "1")
                            .with(user(unauthorizedUsername)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        } finally {
            jdbcTemplate.update("DELETE FROM principal_account WHERE username = ?", unauthorizedUsername);
        }
        mockMvc.perform(get("/api/v1/runtime-integrations")
                        .param("scopeId", "1")
                        .param("runtimeKey", "runtime-without-records-" + suffix)
                        .with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private void postEvent(String token, String integrationId, Map<String, Object> event) throws Exception {
        mockMvc.perform(post("/api/v1/runtime-integrations/{integrationId}/events", integrationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrationId").value(integrationId));
    }

    private String createToken(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tokens")
                        .with(user("admin"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"scopes\":[\"telemetry:write\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private Map<String, Object> registration(String targetKey, String configurationDigest) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("scopeId", 1);
        value.put("runtimeKey", "codex-cli");
        value.put("runtimeVersion", "0.151.0");
        value.put("targetKey", targetKey);
        value.put("adapterVersion", "0.1.0");
        value.put("configurationDigest", configurationDigest);
        value.put("installationState", "ACTION_REQUIRED");
        value.put("healthStatus", "DEGRADED");
        return value;
    }

    private Map<String, Object> event(String eventId, int sequence, String installationState,
                                      String healthStatus, String result, String reason) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("eventId", eventId);
        value.put("eventSequence", sequence);
        value.put("eventType", "CHECK_COMPLETED");
        value.put("stage", "VERIFYING");
        value.put("result", result);
        value.put("installationState", installationState);
        value.put("healthStatus", healthStatus);
        value.put("occurredAt", "2026-09-03T08:00:0" + sequence + "Z");
        if (reason != null) {
            value.put("failureStage", "VERIFYING");
            value.put("errorCode", "SELF_CHECK_FAILED");
            value.put("errorReason", reason);
        }
        return value;
    }

    private JsonNode findById(JsonNode items, String integrationId) {
        for (JsonNode item : items) {
            if (integrationId.equals(item.path("integrationId").asText())) return item;
        }
        throw new AssertionError("授权列表中缺少刚登记的运行时接入实例");
    }

    private String digest(String suffix, char fill) {
        StringBuilder value = new StringBuilder(suffix);
        while (value.length() < 64) value.append(fill);
        return value.substring(0, 64).toLowerCase();
    }
}
