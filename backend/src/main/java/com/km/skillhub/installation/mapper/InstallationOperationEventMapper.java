package com.km.skillhub.installation.mapper;

import com.km.skillhub.installation.model.entity.InstallationOperationEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface InstallationOperationEventMapper {
    @Select("SELECT id, event_id, operation_id, event_sequence, event_type, event_state, payload::text AS payload, "
            + "occurred_at, received_at FROM installation_operation_event WHERE event_id = #{eventId}")
    InstallationOperationEventEntity findByEventId(String eventId);

    @Select("SELECT id, event_id, operation_id, event_sequence, event_type, event_state, payload::text AS payload, "
            + "occurred_at, received_at FROM installation_operation_event "
            + "WHERE operation_id = #{operationId} ORDER BY event_sequence DESC LIMIT 1")
    InstallationOperationEventEntity findLatest(@Param("operationId") Long operationId);

    @Insert("INSERT INTO installation_operation_event(event_id, operation_id, event_sequence, event_type, event_state, "
            + "payload, occurred_at) VALUES(#{eventId}, #{operationId}, #{eventSequence}, #{eventType}, #{eventState}, "
            + "CAST(#{payload} AS jsonb), #{occurredAt}) ON CONFLICT (event_id) DO NOTHING")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(InstallationOperationEventEntity entity);
}
