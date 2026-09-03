package com.km.skillhub.policy.mapper;

import com.km.skillhub.policy.model.entity.RetentionPolicyVersionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RetentionPolicyMapper {
    @Insert("INSERT INTO retention_policy_version(scope_type, scope_id, data_class, retention_type, retention_seconds, "
            + "policy_version, effective_at, approved_by, created_by) VALUES(#{scopeType}, #{scopeId}, #{dataClass}, "
            + "#{retentionType}, #{retentionSeconds}, #{policyVersion}, #{effectiveAt}, #{approvedBy}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(RetentionPolicyVersionEntity entity);
    @Select("SELECT id, scope_type, scope_id, data_class, retention_type, retention_seconds, policy_version, "
            + "effective_at, approved_by, created_by FROM retention_policy_version ORDER BY effective_at DESC, id DESC")
    List<RetentionPolicyVersionEntity> findAll();
}
