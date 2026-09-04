package com.km.skillhub.telemetry.mapper;

import com.km.skillhub.telemetry.model.entity.AgentTraceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentTraceMapper {
    @Insert("INSERT INTO agent_trace(scope_id, trace_id, session_id, runtime_key, runtime_version, agent_id, "
            + "parent_agent_id, status, event_count, started_at, ended_at, completeness) VALUES(#{scopeId}, "
            + "#{traceId}, #{sessionId}, #{runtimeKey}, #{runtimeVersion}, #{agentId}, #{parentAgentId}, #{status}, "
            + "COALESCE(#{eventCount}, 0), #{startedAt}, #{endedAt}, CAST(#{completeness} AS jsonb))")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AgentTraceEntity entity);

    @Select("SELECT id, scope_id, trace_id, session_id, runtime_key, runtime_version, agent_id, parent_agent_id, "
            + "status, event_count, started_at, ended_at, completeness::text AS completeness, updated_at, "
            + "row_version FROM agent_trace WHERE scope_id = #{scopeId} AND trace_id = #{traceId}")
    AgentTraceEntity findByScopeAndTraceId(@Param("scopeId") Long scopeId,
                                            @Param("traceId") String traceId);
}
