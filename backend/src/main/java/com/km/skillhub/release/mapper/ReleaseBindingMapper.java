package com.km.skillhub.release.mapper;

import com.km.skillhub.release.model.entity.ReleaseBindingEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReleaseBindingMapper {
    @Select("SELECT id, asset_id, version_digest, scope_type, scope_id, binding_state, is_current, "
            + "policy_version, decision_id, effective_at, released_at, created_by FROM release_binding "
            + "WHERE asset_id = #{assetId} AND scope_type = #{scopeType} AND scope_id = #{scopeId} "
            + "AND is_current = TRUE")
    ReleaseBindingEntity findCurrent(@Param("assetId") Long assetId, @Param("scopeType") String scopeType,
                                     @Param("scopeId") Long scopeId);

    @Update("UPDATE release_binding SET is_current = FALSE, binding_state = 'REPLACED' "
            + "WHERE asset_id = #{assetId} AND scope_type = #{scopeType} AND scope_id = #{scopeId} "
            + "AND is_current = TRUE")
    int clearCurrent(@Param("assetId") Long assetId, @Param("scopeType") String scopeType,
                     @Param("scopeId") Long scopeId);

    @Insert("INSERT INTO release_binding(asset_id, version_digest, scope_type, scope_id, binding_state, "
            + "is_current, policy_version, decision_id, effective_at, released_at, created_by) "
            + "VALUES(#{assetId}, #{versionDigest}, #{scopeType}, #{scopeId}, #{bindingState}, #{current}, "
            + "#{policyVersion}, #{decisionId}, #{effectiveAt}, #{releasedAt}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ReleaseBindingEntity entity);

    @Update("UPDATE release_binding SET is_current = FALSE, binding_state = 'REVOKED' "
            + "WHERE version_digest = #{versionDigest} AND scope_type = #{scopeType} AND scope_id = #{scopeId} "
            + "AND is_current = TRUE")
    int revokeCurrent(@Param("versionDigest") String versionDigest, @Param("scopeType") String scopeType,
                      @Param("scopeId") Long scopeId);
}
