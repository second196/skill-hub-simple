package com.km.skillhub.audit.mapper;

import com.km.skillhub.audit.model.AuditLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AuditLogMapper {
    @Insert("INSERT INTO audit_log(actor_id, actor_type, action, object_type, object_id, before_state, after_state, "
            + "reason, scope_type, scope_id, policy_version) VALUES(#{actorId}, #{actorType}, #{action}, #{objectType}, "
            + "#{objectId}, CAST(#{beforeState} AS jsonb), CAST(#{afterState} AS jsonb), #{reason}, #{scopeType}, "
            + "#{scopeId}, #{policyVersion})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AuditLogEntity entity);

    @Select("SELECT id, actor_id, actor_type, action, object_type, object_id, before_state::text AS before_state, "
            + "after_state::text AS after_state, reason, scope_type, scope_id, policy_version, occurred_at "
            + "FROM audit_log ORDER BY occurred_at DESC, id DESC LIMIT 100")
    List<AuditLogEntity> findRecent();
}
