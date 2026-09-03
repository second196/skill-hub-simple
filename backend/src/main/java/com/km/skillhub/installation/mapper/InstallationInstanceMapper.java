package com.km.skillhub.installation.mapper;

import com.km.skillhub.installation.model.entity.InstallationInstanceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface InstallationInstanceMapper {
    @Select("SELECT id, asset_id, current_version_digest, runtime_key, runtime_version, target_type, target_key, "
            + "scope_id, desired_state, skill_state, tracker_state, overall_state, health_status, last_health_at, "
            + "last_error_code, last_error_reason, installed_at, created_by, updated_by, created_at, updated_at, row_version "
            + "FROM installation_instance WHERE id = #{id}")
    InstallationInstanceEntity findById(Long id);

    @Select("SELECT id, asset_id, current_version_digest, runtime_key, runtime_version, target_type, target_key, "
            + "scope_id, desired_state, skill_state, tracker_state, overall_state, health_status, last_health_at, "
            + "last_error_code, last_error_reason, installed_at, created_by, updated_by, created_at, updated_at, row_version "
            + "FROM installation_instance WHERE asset_id = #{assetId} AND runtime_key = #{runtimeKey} "
            + "AND target_type = #{targetType} AND target_key = #{targetKey} AND scope_id = #{scopeId}")
    InstallationInstanceEntity findByTarget(@Param("assetId") Long assetId, @Param("runtimeKey") String runtimeKey,
                                            @Param("targetType") String targetType, @Param("targetKey") String targetKey,
                                            @Param("scopeId") Long scopeId);

    @Select({"<script>",
            "SELECT id, asset_id, current_version_digest, runtime_key, runtime_version, target_type, target_key,",
            "scope_id, desired_state, skill_state, tracker_state, overall_state, health_status, last_health_at,",
            "last_error_code, last_error_reason, installed_at, created_by, updated_by, created_at, updated_at, row_version",
            "FROM installation_instance WHERE scope_id = #{scopeId}",
            "<if test='assetId != null'> AND asset_id = #{assetId}</if>",
            "<if test='runtimeKey != null and runtimeKey != \"\"'> AND runtime_key = #{runtimeKey}</if>",
            "ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}",
            "</script>"})
    List<InstallationInstanceEntity> findPage(@Param("scopeId") Long scopeId, @Param("assetId") Long assetId,
                                               @Param("runtimeKey") String runtimeKey, @Param("limit") int limit,
                                               @Param("offset") int offset);

    @Insert("INSERT INTO installation_instance(asset_id, current_version_digest, runtime_key, runtime_version, "
            + "target_type, target_key, scope_id, desired_state, skill_state, tracker_state, overall_state, "
            + "health_status, created_by, updated_by) VALUES(#{assetId}, #{currentVersionDigest}, #{runtimeKey}, "
            + "#{runtimeVersion}, #{targetType}, #{targetKey}, #{scopeId}, #{desiredState}, #{skillState}, "
            + "#{trackerState}, #{overallState}, #{healthStatus}, #{createdBy}, #{updatedBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(InstallationInstanceEntity entity);

    @Update("UPDATE installation_instance SET current_version_digest = #{currentVersionDigest}, desired_state = #{desiredState}, "
            + "skill_state = #{skillState}, tracker_state = #{trackerState}, overall_state = #{overallState}, "
            + "health_status = #{healthStatus}, last_health_at = #{lastHealthAt}, last_error_code = #{lastErrorCode}, "
            + "last_error_reason = #{lastErrorReason}, installed_at = #{installedAt}, updated_by = #{updatedBy}, "
            + "row_version = row_version + 1 WHERE id = #{id} AND row_version = #{rowVersion}")
    int updateProgress(InstallationInstanceEntity entity);

    @Select("SELECT id, asset_id, current_version_digest, runtime_key, runtime_version, target_type, target_key, "
            + "scope_id, desired_state, skill_state, tracker_state, overall_state, health_status, last_health_at, "
            + "last_error_code, last_error_reason, installed_at, created_by, updated_by, created_at, updated_at, row_version "
            + "FROM installation_instance WHERE scope_id = #{scopeId} AND current_version_digest = #{versionDigest}")
    List<InstallationInstanceEntity> findByScopeAndVersion(@Param("scopeId") Long scopeId,
                                                            @Param("versionDigest") String versionDigest);
}
