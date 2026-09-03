package com.km.skillhub.installation.mapper;

import com.km.skillhub.installation.model.entity.InstallationOperationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface InstallationOperationMapper {
    @Select("SELECT id, operation_id, request_id, operation_type, installation_instance_id, asset_id, "
            + "previous_version_digest, target_version_digest, runtime_key, runtime_version, scope_id, operation_stage, "
            + "operation_state, rollback_state, failure_stage, error_code, error_reason, actor_id, requested_at, "
            + "started_at, completed_at, created_at, row_version FROM installation_operation WHERE operation_id = #{operationId}")
    InstallationOperationEntity findByOperationId(String operationId);

    @Select("SELECT id, operation_id, request_id, operation_type, installation_instance_id, asset_id, "
            + "previous_version_digest, target_version_digest, runtime_key, runtime_version, scope_id, operation_stage, "
            + "operation_state, rollback_state, failure_stage, error_code, error_reason, actor_id, requested_at, "
            + "started_at, completed_at, created_at, row_version FROM installation_operation "
            + "WHERE request_id = #{requestId} AND installation_instance_id = #{instanceId}")
    InstallationOperationEntity findByRequest(@Param("requestId") String requestId, @Param("instanceId") Long instanceId);

    @Update("UPDATE installation_operation SET operation_stage = #{operationStage}, operation_state = #{operationState}, "
            + "rollback_state = #{rollbackState}, failure_stage = #{failureStage}, error_code = #{errorCode}, "
            + "error_reason = #{errorReason}, started_at = #{startedAt}, completed_at = #{completedAt} "
            + "WHERE operation_id = #{operationId}")
    int updateProgress(InstallationOperationEntity entity);

    @Select("SELECT id, operation_id, request_id, operation_type, installation_instance_id, asset_id, "
            + "previous_version_digest, target_version_digest, runtime_key, runtime_version, scope_id, operation_stage, "
            + "operation_state, rollback_state, failure_stage, error_code, error_reason, actor_id, requested_at, "
            + "started_at, completed_at, created_at, row_version FROM installation_operation "
            + "WHERE installation_instance_id = #{instanceId} ORDER BY created_at DESC LIMIT 1")
    InstallationOperationEntity findLatestByInstance(@Param("instanceId") Long instanceId);

    @Insert("INSERT INTO installation_operation(operation_id, request_id, operation_type, installation_instance_id, asset_id, "
            + "previous_version_digest, target_version_digest, runtime_key, runtime_version, scope_id, operation_stage, "
            + "operation_state, rollback_state, actor_id) VALUES(#{operationId}, #{requestId}, #{operationType}, "
            + "#{installationInstanceId}, #{assetId}, #{previousVersionDigest}, #{targetVersionDigest}, #{runtimeKey}, "
            + "#{runtimeVersion}, #{scopeId}, #{operationStage}, #{operationState}, #{rollbackState}, #{actorId})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(InstallationOperationEntity entity);
}
