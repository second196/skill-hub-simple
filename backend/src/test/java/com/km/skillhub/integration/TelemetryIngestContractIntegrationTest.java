package com.km.skillhub.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
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
@TestPropertySource(properties = "skillhub.telemetry.ingest.max-payload-bytes=2048")
class TelemetryIngestContractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void acceptsStandardTraceAndReturnsOriginalBatchForSameRequest() throws Exception {
        String suffix = suffix();
        String token = createToken("Trace 上报" + suffix, "telemetry:write");
        String requestId = "trace-" + suffix;
        String payload = tracePayload(suffix, false, "secret-token-123 C:\\\\Users\\\\tester\\\\project");

        MvcResult first = postTelemetry("/api/v1/telemetry/otlp/v1/traces", token, requestId, payload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicate").value(0))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn();
        long batchId = objectMapper.readTree(first.getResponse().getContentAsString()).get("batchId").asLong();

        postTelemetry("/api/v1/telemetry/otlp/v1/traces", token, requestId, payload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.accepted").value(1));
        mockMvc.perform(get("/api/v1/telemetry/ingest-batches/{requestId}", requestId)
                        .with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.received").value(1));

        String stored = jdbcTemplate.queryForObject(
                "SELECT attributes::text FROM runtime_event WHERE batch_id = ?", String.class, batchId);
        assertFalse(stored.contains("secret-token-123"));
        assertFalse(stored.contains("C:\\\\Users\\\\tester"));
    }

    @Test
    void rejectsChangedPayloadForSameRequestId() throws Exception {
        String suffix = suffix();
        String token = createToken("幂等冲突" + suffix, "telemetry:write");
        String requestId = "conflict-" + suffix;

        postTelemetry("/api/v1/telemetry/otlp/v1/traces", token, requestId,
                tracePayload(suffix, false, "normal"))
                .andExpect(status().isOk());
        postTelemetry("/api/v1/telemetry/otlp/v1/traces", token, requestId,
                tracePayload(suffix, false, "changed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM telemetry_ingest_batch WHERE request_id = ?", Integer.class, requestId));
    }

    @Test
    void acceptsValidItemsAndReportsSemanticRejects() throws Exception {
        String suffix = suffix();
        String token = createToken("部分上报" + suffix, "telemetry:write");
        String requestId = "partial-" + suffix;

        postTelemetry("/api/v1/telemetry/otlp/v1/traces", token, requestId,
                tracePayload(suffix, true, "normal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.rejectionReasons.EVENT_REQUIRED_FIELD_MISSING").value(1));
    }

    @Test
    void acceptsStandardLogAndRejectsMalformedOrOversizedPayloads() throws Exception {
        String suffix = suffix();
        String token = createToken("日志上报" + suffix, "telemetry:write");

        postTelemetry("/api/v1/telemetry/otlp/v1/logs", token, "log-" + suffix, logPayload(suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        postTelemetry("/api/v1/telemetry/otlp/v1/traces", token, "bad-" + suffix, "{bad-json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OTLP_STRUCTURE"));

        StringBuilder oversized = new StringBuilder("{\"padding\":\"");
        for (int index = 0; index < 4096; index++) oversized.append('x');
        oversized.append("\"}");
        postTelemetry("/api/v1/telemetry/otlp/v1/traces", token, "large-" + suffix, oversized.toString())
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    private org.springframework.test.web.servlet.ResultActions postTelemetry(
            String path, String token, String requestId, String payload) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + token)
                .header("X-Request-Id", requestId)
                .contentType(APPLICATION_JSON)
                .content(payload));
    }

    private String createToken(String name, String scope) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tokens")
                        .with(user("admin"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"scopes\":[\"" + scope + "\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        return payload.get("token").asText();
    }

    private String tracePayload(String suffix, boolean includeInvalid, String unsafeValue) {
        long start = Instant.now().toEpochMilli() * 1000000L;
        long end = start + 25000000L;
        String validSpan = "{\"traceId\":\"" + hex(suffix, 32, 'a') + "\","
                + "\"spanId\":\"" + hex(suffix, 16, 'b') + "\",\"name\":\"skill demo\","
                + "\"startTimeUnixNano\":\"" + start + "\",\"endTimeUnixNano\":\"" + end + "\","
                + "\"attributes\":["
                + attribute("session.id", "session-" + suffix) + ","
                + attribute("skill.name", "demo-skill") + ","
                + attribute("event.id", "event-" + suffix) + ","
                + attribute("custom.note", unsafeValue) + ","
                + attribute("prompt", "must-not-store") + "],"
                + "\"status\":{\"code\":1}}";
        String invalidSpan = includeInvalid
                ? ",{\"traceId\":\"" + hex(suffix, 32, 'c') + "\",\"name\":\"invalid\","
                + "\"startTimeUnixNano\":\"" + start + "\",\"endTimeUnixNano\":\"" + end + "\"}"
                : "";
        return "{\"resourceSpans\":[{\"resource\":{\"attributes\":["
                + attribute("skillhub.scope.id", "1", "intValue") + ","
                + attribute("skillhub.schema.version", "1.0") + ","
                + attribute("service.name", "codex-cli") + ","
                + attribute("service.version", "0.1.0") + "]},"
                + "\"scopeSpans\":[{\"spans\":[" + validSpan + invalidSpan + "]}]}]}";
    }

    private String logPayload(String suffix) {
        long timestamp = Instant.now().toEpochMilli() * 1000000L;
        return "{\"resourceLogs\":[{\"resource\":{\"attributes\":["
                + attribute("skillhub.scope.id", "1", "intValue") + ","
                + attribute("service.name", "claude-code") + ","
                + attribute("service.version", "1.0.0") + "]},"
                + "\"scopeLogs\":[{\"logRecords\":[{\"timeUnixNano\":\"" + timestamp + "\","
                + "\"traceId\":\"" + hex(suffix, 32, 'd') + "\","
                + "\"spanId\":\"" + hex(suffix, 16, 'e') + "\",\"severityText\":\"INFO\","
                + "\"attributes\":[" + attribute("session.id", "session-" + suffix) + ","
                + attribute("event.id", "log-event-" + suffix) + "]}]}]}]}";
    }

    private String attribute(String key, String value) {
        return attribute(key, value, "stringValue");
    }

    private String attribute(String key, String value, String valueType) {
        return "{\"key\":\"" + key + "\",\"value\":{\"" + valueType + "\":\""
                + json(value) + "\"}}";
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String hex(String suffix, int length, char fill) {
        String value = suffix.toLowerCase() + repeat(fill, length);
        return value.substring(0, length);
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
