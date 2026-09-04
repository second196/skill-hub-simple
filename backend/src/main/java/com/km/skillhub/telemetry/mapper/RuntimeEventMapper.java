package com.km.skillhub.telemetry.mapper;

import com.km.skillhub.telemetry.model.entity.RuntimeEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

@Mapper
public interface RuntimeEventMapper {
    @Insert("INSERT INTO runtime_event_dedup(event_id, batch_id, occurred_at) "
            + "VALUES(#{eventId}, #{batchId}, #{occurredAt}) ON CONFLICT (event_id) DO NOTHING")
    int insertDedup(@Param("eventId") String eventId,
                    @Param("batchId") Long batchId,
                    @Param("occurredAt") OffsetDateTime occurredAt);

    @Insert("INSERT INTO runtime_event(event_id, batch_id, schema_version, event_type, scope_id, runtime_key, "
            + "runtime_version, tracker_version, session_id, trace_id, span_id, parent_span_id, sequence, "
            + "agent_id, parent_agent_id, model_name, tool_name, mcp_server, skill_name, version_digest, "
            + "invocation_id, trigger_type, status, duration_ms, input_tokens, output_tokens, cost, "
            + "version_unknown, version_unknown_reason, attributes, missing_fields, privacy_actions, occurred_at) "
            + "VALUES(#{eventId}, #{batchId}, #{schemaVersion}, #{eventType}, #{scopeId}, #{runtimeKey}, "
            + "#{runtimeVersion}, #{trackerVersion}, #{sessionId}, #{traceId}, #{spanId}, #{parentSpanId}, "
            + "#{sequence}, #{agentId}, #{parentAgentId}, #{modelName}, #{toolName}, #{mcpServer}, #{skillName}, "
            + "#{versionDigest}, #{invocationId}, #{triggerType}, #{status}, #{durationMs}, #{inputTokens}, "
            + "#{outputTokens}, #{cost}, #{versionUnknown}, #{versionUnknownReason}, CAST(#{attributes} AS jsonb), "
            + "CAST(#{missingFields} AS jsonb), CAST(#{privacyActions} AS jsonb), #{occurredAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(RuntimeEventEntity entity);
}
