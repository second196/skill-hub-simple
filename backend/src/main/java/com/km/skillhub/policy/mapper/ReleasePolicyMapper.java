package com.km.skillhub.policy.mapper;

import com.km.skillhub.policy.model.entity.ReleasePolicyVersionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ReleasePolicyMapper {
    @Insert("INSERT INTO release_policy_version(scope_type, scope_id, policy_version, effective_at, "
            + "minimum_valid_cases, gray_ratio, observation_window_seconds, minimum_valid_calls, "
            + "rollback_conditions, auto_release_conditions, created_by) VALUES(#{scopeType}, #{scopeId}, "
            + "#{policyVersion}, #{effectiveAt}, #{minimumValidCases}, #{grayRatio}, #{observationWindowSeconds}, "
            + "#{minimumValidCalls}, CAST(#{rollbackConditions} AS jsonb), CAST(#{autoReleaseConditions} AS jsonb), #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ReleasePolicyVersionEntity entity);

    @Select("SELECT id, scope_type, scope_id, policy_version, effective_at, minimum_valid_cases, gray_ratio, "
            + "observation_window_seconds, minimum_valid_calls, rollback_conditions::text AS rollback_conditions, "
            + "auto_release_conditions::text AS auto_release_conditions, created_by FROM release_policy_version "
            + "WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} AND effective_at <= CURRENT_TIMESTAMP "
            + "ORDER BY effective_at DESC, id DESC LIMIT 1")
    ReleasePolicyVersionEntity findEffective(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    @Select("WITH RECURSIVE ancestors AS ("
            + "SELECT id, scope_type, 0 AS depth FROM governance_scope WHERE id = #{scopeId} "
            + "UNION ALL SELECT parent.id, parent.scope_type, ancestors.depth + 1 FROM governance_scope parent "
            + "JOIN ancestors ON ancestors.id = parent_scope_id) "
            + "SELECT rp.id, rp.scope_type, rp.scope_id, rp.policy_version, rp.effective_at, rp.minimum_valid_cases, "
            + "rp.gray_ratio, rp.observation_window_seconds, rp.minimum_valid_calls, "
            + "rp.rollback_conditions::text AS rollback_conditions, rp.auto_release_conditions::text AS auto_release_conditions, rp.created_by "
            + "FROM release_policy_version rp JOIN ancestors a ON a.scope_type = rp.scope_type AND a.id = rp.scope_id "
            + "WHERE rp.effective_at <= CURRENT_TIMESTAMP ORDER BY a.depth, rp.effective_at DESC, rp.id DESC LIMIT 1")
    ReleasePolicyVersionEntity findEffectiveHierarchy(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    @Select("SELECT id, scope_type, scope_id, policy_version, effective_at, minimum_valid_cases, gray_ratio, "
            + "observation_window_seconds, minimum_valid_calls, rollback_conditions::text AS rollback_conditions, "
            + "auto_release_conditions::text AS auto_release_conditions, created_by FROM release_policy_version "
            + "ORDER BY effective_at DESC, id DESC")
    List<ReleasePolicyVersionEntity> findAll();
}
