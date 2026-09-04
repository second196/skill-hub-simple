package com.km.skillhub.telemetry.mapper;

import com.km.skillhub.telemetry.model.entity.TelemetryIngestBatchEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TelemetryIngestBatchMapper {
    @Insert("INSERT INTO telemetry_ingest_batch(request_id, actor_id, token_id, scope_id, runtime_key, "
            + "payload_digest, received_count, accepted_count, duplicate_count, rejected_count, status, "
            + "error_summary) VALUES(#{requestId}, #{actorId}, #{tokenId}, #{scopeId}, #{runtimeKey}, "
            + "#{payloadDigest}, #{receivedCount}, COALESCE(#{acceptedCount}, 0), "
            + "COALESCE(#{duplicateCount}, 0), COALESCE(#{rejectedCount}, 0), #{status}, "
            + "CAST(COALESCE(#{errorSummary}, '{}') AS jsonb))")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(TelemetryIngestBatchEntity entity);

    @Update("UPDATE telemetry_ingest_batch SET accepted_count = #{acceptedCount}, "
            + "duplicate_count = #{duplicateCount}, rejected_count = #{rejectedCount}, status = #{status}, "
            + "completed_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int complete(@Param("id") Long id,
                 @Param("acceptedCount") int acceptedCount,
                 @Param("duplicateCount") int duplicateCount,
                 @Param("rejectedCount") int rejectedCount,
                 @Param("status") String status);

    @Select("SELECT id, request_id, actor_id, token_id, scope_id, runtime_key, payload_digest, received_count, "
            + "accepted_count, duplicate_count, rejected_count, status, error_summary::text AS error_summary, "
            + "received_at, completed_at FROM telemetry_ingest_batch WHERE request_id = #{requestId}")
    TelemetryIngestBatchEntity findByRequestId(String requestId);
}
