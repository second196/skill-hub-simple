package com.km.skillhub.telemetry.mapper;

import com.km.skillhub.telemetry.model.entity.TelemetryAggregationCheckpointEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TelemetryAggregationCheckpointMapper {
    @Insert("INSERT INTO telemetry_aggregation_checkpoint(consumer_key, last_batch_id, window_start, window_end) "
            + "VALUES(#{consumerKey}, #{lastBatchId}, #{windowStart}, #{windowEnd})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(TelemetryAggregationCheckpointEntity entity);

    @Select("SELECT id, consumer_key, last_batch_id, window_start, window_end, updated_at, row_version "
            + "FROM telemetry_aggregation_checkpoint WHERE consumer_key = #{consumerKey}")
    TelemetryAggregationCheckpointEntity findByConsumerKey(String consumerKey);
}
