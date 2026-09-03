package com.km.skillhub.integration;

import com.km.skillhub.integration.event.RedisStreamsOutboxDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxDeliveryIntegrationTest {
    private static final String STREAM_KEY = "skillhub:governance:events";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisStreamsOutboxDispatcher dispatcher;

    private String eventId;

    @AfterEach
    void cleanUp() {
        if (eventId != null) {
            jdbcTemplate.update("DELETE FROM governance_event_outbox WHERE event_id = ?", eventId);
            List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                    .range(STREAM_KEY, Range.unbounded());
            for (MapRecord<String, Object, Object> record : records) {
                if (eventId.equals(record.getValue().get("eventId"))) {
                    stringRedisTemplate.opsForStream().delete(STREAM_KEY, record.getId());
                }
            }
        }
    }

    @Test
    void dispatchesPendingOutboxEventToRedisStream() {
        eventId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO governance_event_outbox "
                        + "(event_id, event_type, aggregate_type, aggregate_id, payload, state, retry_count) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'PENDING', 0)",
                eventId, "INTEGRATION_VALIDATION", "TEST", "outbox-test", "{\"versionDigest\":\"validation-digest\"}");

        assertEquals(1, dispatcher.dispatchPending());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM governance_event_outbox WHERE event_id = ? AND state = 'PUBLISHED'",
                Integer.class, eventId).intValue());

        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                .range(STREAM_KEY, Range.unbounded());
        assertTrue(records.stream().anyMatch(record -> eventId.equals(record.getValue().get("eventId"))));
    }
}
