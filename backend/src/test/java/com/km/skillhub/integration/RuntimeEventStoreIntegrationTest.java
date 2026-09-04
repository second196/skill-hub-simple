package com.km.skillhub.integration;

import com.km.skillhub.telemetry.model.entity.RuntimeEventEntity;
import com.km.skillhub.telemetry.model.entity.TelemetryIngestBatchEntity;
import com.km.skillhub.telemetry.store.TelemetryAppendResult;
import com.km.skillhub.telemetry.store.TelemetryEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class RuntimeEventStoreIntegrationTest {
    @Autowired
    private TelemetryEventStore eventStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appendsBatchAndDeduplicatesEventAcrossRequests() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        RuntimeEventEntity event = event("event-" + suffix, OffsetDateTime.now(ZoneOffset.UTC));

        TelemetryAppendResult first = eventStore.appendBatch(batch("request-a-" + suffix, 'a'),
                Collections.singletonList(event));
        TelemetryAppendResult second = eventStore.appendBatch(batch("request-b-" + suffix, 'b'),
                Collections.singletonList(event));

        assertEquals(1, first.getAccepted());
        assertEquals(0, first.getDuplicate());
        assertEquals(0, second.getAccepted());
        assertEquals(1, second.getDuplicate());
        assertEquals(1, count("runtime_event", "event_id", event.getEventId()));
        assertEquals(1, count("runtime_event_dedup", "event_id", event.getEventId()));
        assertEquals(2, countLike("telemetry_aggregation_outbox", "event_id", "telemetry-batch-%" + suffix + "%"));
    }

    @Test
    void rollsBackBatchDedupAndOutboxWhenAnEventHasNoPartition() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        TelemetryIngestBatchEntity batch = batch("request-rollback-" + suffix, 'c');
        RuntimeEventEntity valid = event("event-valid-" + suffix, OffsetDateTime.now(ZoneOffset.UTC));
        RuntimeEventEntity outside = event("event-outside-" + suffix,
                OffsetDateTime.of(2035, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        assertThrows(DataAccessException.class,
                () -> eventStore.appendBatch(batch, Arrays.asList(valid, outside)));

        assertEquals(0, count("telemetry_ingest_batch", "request_id", batch.getRequestId()));
        assertEquals(0, count("runtime_event_dedup", "event_id", valid.getEventId()));
        assertEquals(0, count("runtime_event_dedup", "event_id", outside.getEventId()));
        assertEquals(0, count("runtime_event", "event_id", valid.getEventId()));
        assertEquals(0, countLike("telemetry_aggregation_outbox", "event_id",
                "telemetry-batch-" + batch.getRequestId()));
    }

    private TelemetryIngestBatchEntity batch(String requestId, char digestCharacter) {
        TelemetryIngestBatchEntity value = new TelemetryIngestBatchEntity();
        value.setRequestId(requestId);
        value.setActorId("admin");
        value.setTokenId("token-test");
        value.setScopeId(1L);
        value.setRuntimeKey("codex-cli");
        value.setPayloadDigest(repeat(digestCharacter, 64));
        value.setReceivedCount(1);
        value.setRejectedCount(0);
        value.setStatus("ACCEPTED");
        value.setErrorSummary("{}");
        return value;
    }

    private RuntimeEventEntity event(String eventId, OffsetDateTime occurredAt) {
        RuntimeEventEntity value = new RuntimeEventEntity();
        value.setEventId(eventId);
        value.setSchemaVersion("1.0");
        value.setEventType("SPAN_COMPLETED");
        value.setScopeId(1L);
        value.setRuntimeKey("codex-cli");
        value.setRuntimeVersion("0.151.0");
        value.setSessionId("session-test");
        value.setTraceId("trace-test");
        value.setSpanId(eventId.substring(0, Math.min(eventId.length(), 64)));
        value.setSequence(1L);
        value.setStatus("SUCCEEDED");
        value.setDurationMs(25L);
        value.setInputTokens(10L);
        value.setOutputTokens(5L);
        value.setCost(new BigDecimal("0.0100"));
        value.setVersionUnknown(true);
        value.setVersionUnknownReason("运行时未提供明确技能版本");
        value.setAttributes("{}");
        value.setMissingFields("[\"versionDigest\"]");
        value.setPrivacyActions("[]");
        value.setOccurredAt(occurredAt);
        return value;
    }

    private int count(String table, String column, String value) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }

    private int countLike(String table, String column, String value) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " LIKE ?",
                Integer.class, value);
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
