package com.km.skillhub.integration.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class GovernanceEventPublisher {
    private final GovernanceEventOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    public GovernanceEventPublisher(GovernanceEventOutboxMapper outboxMapper, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper; this.objectMapper = objectMapper;
    }
    public String publish(String eventType, String aggregateType, String aggregateId, Object payload) {
        return publishToStream(UUID.randomUUID().toString(), "skillhub:governance:events", eventType,
                aggregateType, aggregateId, payload);
    }

    public String publishToStream(String streamKey, String eventType, String aggregateType, String aggregateId,
                                  Object payload) {
        return publishToStream(UUID.randomUUID().toString(), streamKey, eventType, aggregateType, aggregateId, payload);
    }

    public String publishToStream(String eventId, String streamKey, String eventType, String aggregateType,
                                  String aggregateId, Object payload) {
        if (streamKey == null || streamKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Event stream is required");
        }
        GovernanceEventOutboxEntity entity = new GovernanceEventOutboxEntity();
        if (eventId == null || eventId.trim().isEmpty()) throw new IllegalArgumentException("Event ID is required");
        entity.setEventId(eventId); entity.setEventType(eventType);
        entity.setAggregateType(aggregateType); entity.setAggregateId(aggregateId);
        entity.setStreamKey(streamKey);
        try { entity.setPayload(objectMapper.writeValueAsString(payload)); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Event payload is invalid"); }
        entity.setState("PENDING"); entity.setRetryCount(0); outboxMapper.insert(entity); return entity.getEventId();
    }
}
