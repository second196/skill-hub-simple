package com.km.skillhub.telemetry.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.telemetry.mapper.RuntimeEventMapper;
import com.km.skillhub.telemetry.mapper.TelemetryAggregationOutboxMapper;
import com.km.skillhub.telemetry.mapper.TelemetryIngestBatchMapper;
import com.km.skillhub.telemetry.model.entity.RuntimeEventEntity;
import com.km.skillhub.telemetry.model.entity.TelemetryAggregationOutboxEntity;
import com.km.skillhub.telemetry.model.entity.TelemetryIngestBatchEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class PostgresTelemetryEventStore implements TelemetryEventStore {
    private static final String OUTBOX_EVENT_PREFIX = "telemetry-batch-";
    private static final String OUTBOX_EVENT_TYPE = "TELEMETRY_BATCH_ACCEPTED";

    private final TelemetryIngestBatchMapper batchMapper;
    private final RuntimeEventMapper eventMapper;
    private final TelemetryAggregationOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public PostgresTelemetryEventStore(TelemetryIngestBatchMapper batchMapper,
                                       RuntimeEventMapper eventMapper,
                                       TelemetryAggregationOutboxMapper outboxMapper,
                                       ObjectMapper objectMapper) {
        this.batchMapper = batchMapper;
        this.eventMapper = eventMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TelemetryAppendResult appendBatch(TelemetryIngestBatchEntity batch, List<RuntimeEventEntity> events) {
        if (batch == null) {
            throw new IllegalArgumentException("遥测接收批次不能为空");
        }
        if (events == null) {
            throw new IllegalArgumentException("遥测事件列表不能为空");
        }

        batchMapper.insert(batch);
        int accepted = 0;
        int duplicate = 0;
        for (RuntimeEventEntity event : events) {
            if (event == null) {
                throw new IllegalArgumentException("遥测事件不能为空");
            }
            int inserted = eventMapper.insertDedup(event.getEventId(), batch.getId(), event.getOccurredAt());
            if (inserted == 0) {
                duplicate++;
                continue;
            }
            event.setBatchId(batch.getId());
            eventMapper.insert(event);
            accepted++;
        }

        int rejected = batch.getRejectedCount() == null ? 0 : batch.getRejectedCount();
        batchMapper.complete(batch.getId(), accepted, duplicate, rejected, batch.getStatus());
        outboxMapper.insert(outbox(batch, accepted, duplicate, rejected));
        return new TelemetryAppendResult(batch.getId(), accepted, duplicate, rejected, batch.getStatus());
    }

    private TelemetryAggregationOutboxEntity outbox(TelemetryIngestBatchEntity batch,
                                                      int accepted,
                                                      int duplicate,
                                                      int rejected) {
        TelemetryAggregationOutboxEntity outbox = new TelemetryAggregationOutboxEntity();
        outbox.setEventId(OUTBOX_EVENT_PREFIX + batch.getRequestId());
        outbox.setBatchId(batch.getId());
        outbox.setEventType(OUTBOX_EVENT_TYPE);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("batchId", batch.getId());
        payload.put("requestId", batch.getRequestId());
        payload.put("scopeId", batch.getScopeId());
        payload.put("accepted", accepted);
        payload.put("duplicate", duplicate);
        payload.put("rejected", rejected);
        try {
            outbox.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化遥测聚合通知", exception);
        }
        return outbox;
    }
}
