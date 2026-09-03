package com.km.skillhub.integration.event;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

public interface GovernanceEventOutboxMapper {
    @Insert("INSERT INTO governance_event_outbox(event_id, event_type, aggregate_type, aggregate_id, payload) "
            + "VALUES(#{eventId}, #{eventType}, #{aggregateType}, #{aggregateId}, CAST(#{payload} AS jsonb), "
            + "COALESCE(#{streamKey}, 'skillhub:governance:events'))")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(GovernanceEventOutboxEntity entity);

    @Select("SELECT id, event_id, event_type, aggregate_type, aggregate_id, stream_key, payload::text AS payload, state, "
            + "retry_count, next_retry_at FROM governance_event_outbox WHERE state = 'PENDING' "
            + "AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP) ORDER BY created_at LIMIT 50")
    List<GovernanceEventOutboxEntity> findPending();

    @Update("UPDATE governance_event_outbox SET state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int markPublished(Long id);

    @Update("UPDATE governance_event_outbox SET retry_count = retry_count + 1, next_retry_at = #{nextRetryAt} "
            + "WHERE id = #{id}")
    int markRetry(@org.apache.ibatis.annotations.Param("id") Long id,
                  @org.apache.ibatis.annotations.Param("nextRetryAt") OffsetDateTime nextRetryAt);
}
