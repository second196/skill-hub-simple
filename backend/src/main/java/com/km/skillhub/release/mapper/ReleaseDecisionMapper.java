package com.km.skillhub.release.mapper;

import com.km.skillhub.release.model.entity.ReleaseDecisionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReleaseDecisionMapper {
    @Insert("INSERT INTO release_decision(version_digest, scope_type, scope_id, policy_version, release_mode, "
            + "decision_state, matched_rules, blocking_reasons, request_id, created_by) VALUES(#{versionDigest}, "
            + "#{scopeType}, #{scopeId}, #{policyVersion}, #{releaseMode}, #{decisionState}, "
            + "CAST(#{matchedRules} AS jsonb), CAST(#{blockingReasons} AS jsonb), #{requestId}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ReleaseDecisionEntity entity);

    @Select("SELECT id, version_digest, scope_type, scope_id, policy_version, release_mode, decision_state, "
            + "matched_rules::text AS matched_rules, blocking_reasons::text AS blocking_reasons, request_id, "
            + "decided_at, created_by FROM release_decision WHERE id = #{id}")
    ReleaseDecisionEntity findById(@Param("id") Long id);

    @Update("UPDATE release_decision SET decision_state = #{state} WHERE id = #{id} "
            + "AND decision_state IN ('PENDING_APPROVAL', 'PENDING_GRAY')")
    int updateState(@Param("id") Long id, @Param("state") String state);

    @Update("UPDATE release_decision SET decision_state = 'ROLLED_BACK' WHERE id = #{id} "
            + "AND decision_state IN ('APPROVED', 'PUBLISHED', 'PENDING_GRAY')")
    int rollback(@Param("id") Long id);
}
