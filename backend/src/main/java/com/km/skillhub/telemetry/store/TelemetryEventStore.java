package com.km.skillhub.telemetry.store;

import com.km.skillhub.telemetry.model.entity.RuntimeEventEntity;
import com.km.skillhub.telemetry.model.entity.TelemetryIngestBatchEntity;

import java.util.List;

public interface TelemetryEventStore {
    TelemetryAppendResult appendBatch(TelemetryIngestBatchEntity batch, List<RuntimeEventEntity> events);
}
