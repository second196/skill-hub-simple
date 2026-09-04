package com.km.skillhub.telemetry.mapper;

import com.km.skillhub.telemetry.model.entity.SkillInvocationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkillInvocationMapper {
    @Insert("INSERT INTO skill_invocation(scope_id, invocation_id, trace_id, span_id, parent_span_id, runtime_key, "
            + "skill_name, version_digest, version_unknown, version_unknown_reason, tracker_version, trigger_type, "
            + "status, duration_ms, input_tokens, output_tokens, cost, occurred_at) VALUES(#{scopeId}, "
            + "#{invocationId}, #{traceId}, #{spanId}, #{parentSpanId}, #{runtimeKey}, #{skillName}, "
            + "#{versionDigest}, #{versionUnknown}, #{versionUnknownReason}, #{trackerVersion}, #{triggerType}, "
            + "#{status}, #{durationMs}, #{inputTokens}, #{outputTokens}, #{cost}, #{occurredAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(SkillInvocationEntity entity);

    @Select("SELECT id, scope_id, invocation_id, trace_id, span_id, parent_span_id, runtime_key, skill_name, "
            + "version_digest, version_unknown, version_unknown_reason, tracker_version, trigger_type, status, "
            + "duration_ms, input_tokens, output_tokens, cost, occurred_at, updated_at FROM skill_invocation "
            + "WHERE scope_id = #{scopeId} AND invocation_id = #{invocationId}")
    SkillInvocationEntity findByScopeAndInvocationId(@Param("scopeId") Long scopeId,
                                                      @Param("invocationId") String invocationId);
}
