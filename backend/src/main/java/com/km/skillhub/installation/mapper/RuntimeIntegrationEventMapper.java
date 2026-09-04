package com.km.skillhub.installation.mapper;

import com.km.skillhub.installation.model.entity.RuntimeIntegrationEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

public interface RuntimeIntegrationEventMapper {
    @Select("SELECT id, event_id, runtime_integration_instance_id, event_sequence, event_type, stage, result, "
            + "installation_state, health_status, failure_stage, error_code, error_reason, occurred_at, received_at "
            + "FROM runtime_integration_event WHERE event_id = #{eventId}")
    RuntimeIntegrationEventEntity findByEventId(String eventId);

    @Insert("INSERT INTO runtime_integration_event(event_id, runtime_integration_instance_id, event_sequence, "
            + "event_type, stage, result, installation_state, health_status, failure_stage, error_code, "
            + "error_reason, occurred_at) VALUES(#{eventId}, #{runtimeIntegrationInstanceId}, #{eventSequence}, "
            + "#{eventType}, #{stage}, #{result}, #{installationState}, #{healthStatus}, #{failureStage}, "
            + "#{errorCode}, #{errorReason}, #{occurredAt}) ON CONFLICT DO NOTHING")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(RuntimeIntegrationEventEntity entity);
}
