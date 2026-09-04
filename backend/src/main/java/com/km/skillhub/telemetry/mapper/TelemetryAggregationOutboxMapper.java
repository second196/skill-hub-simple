package com.km.skillhub.telemetry.mapper;

import com.km.skillhub.telemetry.model.entity.TelemetryAggregationOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface TelemetryAggregationOutboxMapper {
    @Insert("INSERT INTO telemetry_aggregation_outbox(event_id, batch_id, event_type, payload) "
            + "VALUES(#{eventId}, #{batchId}, #{eventType}, CAST(#{payload} AS jsonb))")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(TelemetryAggregationOutboxEntity entity);
}
