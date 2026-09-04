package com.km.skillhub.telemetry.mapper;

import com.km.skillhub.telemetry.model.entity.MetricAggregateEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface MetricAggregateMapper {
    @Insert("INSERT INTO metric_aggregate(scope_id, metric_key, definition_version, window_start, window_end, "
            + "dimension_digest, dimensions, sample_count, success_count, error_count, numerator, denominator, "
            + "input_tokens, output_tokens, total_cost, latency_sum_ms, latency_max_ms, completeness_status) "
            + "VALUES(#{scopeId}, #{metricKey}, #{definitionVersion}, #{windowStart}, #{windowEnd}, "
            + "#{dimensionDigest}, CAST(#{dimensions} AS jsonb), COALESCE(#{sampleCount}, 0), "
            + "COALESCE(#{successCount}, 0), COALESCE(#{errorCount}, 0), #{numerator}, #{denominator}, "
            + "COALESCE(#{inputTokens}, 0), COALESCE(#{outputTokens}, 0), COALESCE(#{totalCost}, 0), "
            + "COALESCE(#{latencySumMs}, 0), COALESCE(#{latencyMaxMs}, 0), #{completenessStatus})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(MetricAggregateEntity entity);
}
