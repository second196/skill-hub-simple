package com.km.skillhub.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.telemetry.mapper.TelemetryIngestBatchMapper;
import com.km.skillhub.telemetry.model.entity.RuntimeEventEntity;
import com.km.skillhub.telemetry.model.entity.TelemetryIngestBatchEntity;
import com.km.skillhub.telemetry.model.vo.TelemetryIngestResultVO;
import com.km.skillhub.telemetry.service.OtlpJsonNormalizationService;
import com.km.skillhub.telemetry.service.ServerPrivacyPolicy;
import com.km.skillhub.telemetry.service.TelemetryIngestService;
import com.km.skillhub.telemetry.store.TelemetryAppendResult;
import com.km.skillhub.telemetry.store.TelemetryEventStore;
import com.km.skillhub.token.security.ApiTokenAuthentication;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryIngestServiceTest {
    @Test
    void returnsExistingBatchForSameRequestAndDigest() throws Exception {
        Fixture fixture = new Fixture();
        byte[] payload = "same-payload".getBytes(StandardCharsets.UTF_8);
        TelemetryIngestBatchEntity existing = existing("request-1", digest(payload));
        when(fixture.batchMapper.findByRequestId("request-1")).thenReturn(existing);

        TelemetryIngestResultVO result = fixture.service.ingestTraces("request-1", payload, authentication());

        assertEquals(7L, result.getBatchId());
        assertEquals(1, result.getAccepted());
        verify(fixture.eventStore, never()).appendBatch(any(TelemetryIngestBatchEntity.class), any());
    }

    @Test
    void rejectsChangedPayloadForExistingRequest() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.batchMapper.findByRequestId("request-1"))
                .thenReturn(existing("request-1", digest("first".getBytes(StandardCharsets.UTF_8))));

        TelemetryIngestService.TelemetryIngestException exception = assertThrows(
                TelemetryIngestService.TelemetryIngestException.class,
                () -> fixture.service.ingestTraces("request-1",
                        "second".getBytes(StandardCharsets.UTF_8), authentication()));

        assertEquals("IDEMPOTENCY_CONFLICT", exception.getErrorCode().name());
        verify(fixture.eventStore, never()).appendBatch(any(TelemetryIngestBatchEntity.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizesAndRedactsBeforeAppending() {
        Fixture fixture = new Fixture();
        when(fixture.batchMapper.findByRequestId("request-new")).thenReturn(null);
        when(fixture.eventStore.appendBatch(any(TelemetryIngestBatchEntity.class), any(List.class)))
                .thenReturn(new TelemetryAppendResult(9L, 1, 0, 0, "ACCEPTED"));

        TelemetryIngestResultVO result = fixture.service.ingestTraces(
                "request-new", tracePayload().getBytes(StandardCharsets.UTF_8), authentication());

        ArgumentCaptor<List<RuntimeEventEntity>> events = ArgumentCaptor.forClass(List.class);
        verify(fixture.eventStore).appendBatch(any(TelemetryIngestBatchEntity.class), events.capture());
        RuntimeEventEntity event = events.getValue().get(0);
        assertEquals(1, result.getAccepted());
        assertFalse(event.getAttributes().contains("plain-secret"));
        assertFalse(event.getAttributes().contains("C:\\\\Users\\\\tester"));
        assertFalse(event.getAttributes().contains("must-not-store"));
        assertFalse(event.getAttributes().contains("missing.fields"));
        assertFalse(event.getAttributes().contains("privacy.actions"));
        org.junit.jupiter.api.Assertions.assertTrue(event.getMissingFields().contains("skillName"));
        org.junit.jupiter.api.Assertions.assertTrue(event.getMissingFields().contains("versionDigest"));
        org.junit.jupiter.api.Assertions.assertTrue(event.getPrivacyActions().contains("REDACTED_CREDENTIAL:summary"));
        org.junit.jupiter.api.Assertions.assertTrue(event.getPrivacyActions().contains("DROPPED_PROTECTED_FIELD:prompt"));
    }

    @Test
    void rejectsEventCountAboveServerLimitWithoutAppending() {
        Fixture fixture = new Fixture(0);

        TelemetryIngestService.TelemetryIngestException exception = assertThrows(
                TelemetryIngestService.TelemetryIngestException.class,
                () -> fixture.service.ingestTraces("request-large",
                        tracePayload().getBytes(StandardCharsets.UTF_8), authentication()));

        assertEquals("BATCH_TOO_LARGE", exception.getErrorCode().name());
        verify(fixture.eventStore, never()).appendBatch(any(TelemetryIngestBatchEntity.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotReturnSuccessWhenPostgresTransactionFails() {
        Fixture fixture = new Fixture();
        when(fixture.batchMapper.findByRequestId("request-failed")).thenReturn(null, null);
        when(fixture.eventStore.appendBatch(any(TelemetryIngestBatchEntity.class), any(List.class)))
                .thenThrow(new DataIntegrityViolationException("transaction failed"));

        assertThrows(DataIntegrityViolationException.class,
                () -> fixture.service.ingestTraces("request-failed",
                        tracePayload().getBytes(StandardCharsets.UTF_8), authentication()));
    }

    private static class Fixture {
        private final TelemetryEventStore eventStore = mock(TelemetryEventStore.class);
        private final TelemetryIngestBatchMapper batchMapper = mock(TelemetryIngestBatchMapper.class);
        private final AuthorizationService authorizationService = mock(AuthorizationService.class);
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final OtlpJsonNormalizationService normalizationService = new OtlpJsonNormalizationService(
                new ServerPrivacyPolicy(2000), objectMapper);
        private final TelemetryIngestService service;

        private Fixture() {
            this(1000);
        }

        private Fixture(int maxEvents) {
            service = new TelemetryIngestService(normalizationService, eventStore, batchMapper,
                    authorizationService, objectMapper, maxEvents);
        }
    }

    private static TelemetryIngestBatchEntity existing(String requestId, String payloadDigest) {
        TelemetryIngestBatchEntity value = new TelemetryIngestBatchEntity();
        value.setId(7L);
        value.setRequestId(requestId);
        value.setScopeId(1L);
        value.setRuntimeKey("codex-cli");
        value.setPayloadDigest(payloadDigest);
        value.setReceivedCount(1);
        value.setAcceptedCount(1);
        value.setDuplicateCount(0);
        value.setRejectedCount(0);
        value.setStatus("ACCEPTED");
        value.setErrorSummary("{}");
        return value;
    }

    private static ApiTokenAuthentication authentication() {
        return new ApiTokenAuthentication("admin", 1L, Collections.singletonList(
                new SimpleGrantedAuthority("SCOPE_telemetry:write")));
    }

    private static String tracePayload() {
        long start = Instant.now().toEpochMilli() * 1000000L;
        return "{\"resourceSpans\":[{\"resource\":{\"attributes\":["
                + attr("skillhub.scope.id", "1", "intValue") + ","
                + attr("service.name", "codex-cli", "stringValue") + ","
                + attr("service.version", "0.1.0", "stringValue") + "]},"
                + "\"scopeSpans\":[{\"spans\":[{\"traceId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                + "\"spanId\":\"bbbbbbbbbbbbbbbb\",\"name\":\"tool call\","
                + "\"startTimeUnixNano\":\"" + start + "\",\"endTimeUnixNano\":\"" + (start + 1000000L) + "\"," 
                + "\"attributes\":[" + attr("session.id", "session-1", "stringValue") + ","
                + attr("event.id", "event-1", "stringValue") + ","
                + arrayAttr("missing.fields", "skillName") + ","
                + arrayAttr("privacy.actions", "REDACTED_CREDENTIAL:summary") + ","
                + attr("tool.name", "password=plain-secret C:\\\\Users\\\\tester\\\\project", "stringValue") + ","
                + attr("prompt", "must-not-store", "stringValue") + "]}]}]}]}";
    }

    private static String attr(String key, String value, String valueType) {
        return "{\"key\":\"" + key + "\",\"value\":{\"" + valueType + "\":\""
                + value.replace("\\", "\\\\") + "\"}}";
    }

    private static String arrayAttr(String key, String value) {
        return "{\"key\":\"" + key + "\",\"value\":{\"arrayValue\":{\"values\":[{\"stringValue\":\""
                + value + "\"}]}}}";
    }

    private static String digest(byte[] payload) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload);
        StringBuilder result = new StringBuilder(64);
        for (byte item : hash) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
