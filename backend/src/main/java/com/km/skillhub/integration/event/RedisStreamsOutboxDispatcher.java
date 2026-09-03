package com.km.skillhub.integration.event;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.OffsetDateTime;

@Service
public class RedisStreamsOutboxDispatcher {
    private static final String DEFAULT_STREAM_KEY = "skillhub:governance:events";
    private final GovernanceEventOutboxMapper outboxMapper;
    private final StringRedisTemplate redisTemplate;
    private final long retryDelaySeconds;

    @Autowired
    public RedisStreamsOutboxDispatcher(GovernanceEventOutboxMapper outboxMapper, StringRedisTemplate redisTemplate,
                                        @Value("${skillhub.installation.outbox-retry-seconds:30}") long retryDelaySeconds) {
        this.outboxMapper = outboxMapper; this.redisTemplate = redisTemplate;
        this.retryDelaySeconds = Math.max(1L, retryDelaySeconds);
    }

    public RedisStreamsOutboxDispatcher(GovernanceEventOutboxMapper outboxMapper, StringRedisTemplate redisTemplate) {
        this(outboxMapper, redisTemplate, 30L);
    }

    public int dispatchPending() {
        List<GovernanceEventOutboxEntity> pending = outboxMapper.findPending();
        int published = 0;
        for (GovernanceEventOutboxEntity event : pending) {
            try {
                Map<String, String> fields = new LinkedHashMap<String, String>();
                fields.put("eventId", event.getEventId()); fields.put("eventType", event.getEventType());
                fields.put("aggregateType", event.getAggregateType()); fields.put("aggregateId", event.getAggregateId());
                fields.put("payload", event.getPayload());
                String streamKey = event.getStreamKey() == null ? DEFAULT_STREAM_KEY : event.getStreamKey();
                redisTemplate.opsForStream().add(streamKey, fields);
                outboxMapper.markPublished(event.getId()); published++;
            } catch (RuntimeException exception) {
                outboxMapper.markRetry(event.getId(), OffsetDateTime.now().plusSeconds(retryDelaySeconds));
            }
        }
        return published;
    }
}
