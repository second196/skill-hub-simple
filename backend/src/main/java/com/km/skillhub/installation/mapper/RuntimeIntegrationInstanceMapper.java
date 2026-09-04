package com.km.skillhub.installation.mapper;

import com.km.skillhub.installation.model.entity.RuntimeIntegrationInstanceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface RuntimeIntegrationInstanceMapper {
    String COLUMNS = "id, integration_id, scope_id, runtime_key, runtime_version, target_key, adapter_version, "
            + "configuration_digest, installation_state, health_status, last_event_sequence, failure_stage, "
            + "error_code, error_reason, last_reported_at, created_by, updated_by, created_at, updated_at, row_version";

    @Select("SELECT " + COLUMNS + " FROM runtime_integration_instance WHERE integration_id = #{integrationId}")
    RuntimeIntegrationInstanceEntity findByIntegrationId(String integrationId);

    @Select("SELECT " + COLUMNS + " FROM runtime_integration_instance WHERE scope_id = #{scopeId} "
            + "AND runtime_key = #{runtimeKey} AND target_key = #{targetKey}")
    RuntimeIntegrationInstanceEntity findByTarget(@Param("scopeId") Long scopeId,
                                                   @Param("runtimeKey") String runtimeKey,
                                                   @Param("targetKey") String targetKey);

    @Select("SELECT EXISTS(SELECT 1 FROM runtime_definition WHERE runtime_key = #{runtimeKey} "
            + "AND status = 'ACTIVE' AND capabilities ->> 'reportRuntimeData' = 'true' "
            + "AND (runtime_version = #{runtimeVersion} OR runtime_version = 'initial'))")
    boolean supportsTelemetry(@Param("runtimeKey") String runtimeKey,
                              @Param("runtimeVersion") String runtimeVersion);

    @Select({"<script>", "SELECT " + COLUMNS + " FROM runtime_integration_instance rii",
            "WHERE EXISTS (SELECT 1 FROM principal_scope_role psr JOIN principal_account pa",
            "ON pa.id = psr.principal_id WHERE pa.username = #{username} AND pa.enabled = TRUE",
            "AND psr.scope_id = rii.scope_id)",
            "<if test='scopeId != null'> AND rii.scope_id = #{scopeId}</if>",
            "<if test='runtimeKey != null and runtimeKey != \"\"'> AND rii.runtime_key = #{runtimeKey}</if>",
            "ORDER BY rii.updated_at DESC, rii.id DESC LIMIT #{limit}", "</script>"})
    List<RuntimeIntegrationInstanceEntity> findAuthorized(@Param("username") String username,
                                                           @Param("scopeId") Long scopeId,
                                                           @Param("runtimeKey") String runtimeKey,
                                                           @Param("limit") int limit);

    @Insert("INSERT INTO runtime_integration_instance(integration_id, scope_id, runtime_key, runtime_version, "
            + "target_key, adapter_version, configuration_digest, installation_state, health_status, failure_stage, "
            + "error_code, error_reason, last_reported_at, created_by, updated_by) VALUES(#{integrationId}, "
            + "#{scopeId}, #{runtimeKey}, #{runtimeVersion}, #{targetKey}, #{adapterVersion}, #{configurationDigest}, "
            + "#{installationState}, #{healthStatus}, #{failureStage}, #{errorCode}, #{errorReason}, "
            + "#{lastReportedAt}, #{createdBy}, #{updatedBy}) "
            + "ON CONFLICT (scope_id, runtime_key, target_key) DO NOTHING")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(RuntimeIntegrationInstanceEntity entity);

    @Update("UPDATE runtime_integration_instance SET runtime_version = #{runtimeVersion}, "
            + "adapter_version = #{adapterVersion}, configuration_digest = #{configurationDigest}, "
            + "installation_state = #{installationState}, health_status = #{healthStatus}, "
            + "failure_stage = #{failureStage}, error_code = #{errorCode}, error_reason = #{errorReason}, "
            + "last_reported_at = #{lastReportedAt}, updated_by = #{updatedBy}, updated_at = CURRENT_TIMESTAMP, "
            + "row_version = row_version + 1 WHERE id = #{id} AND row_version = #{rowVersion}")
    int updateRegistration(RuntimeIntegrationInstanceEntity entity);

    @Update("UPDATE runtime_integration_instance SET installation_state = #{installationState}, "
            + "health_status = #{healthStatus}, last_event_sequence = #{lastEventSequence}, "
            + "failure_stage = #{failureStage}, error_code = #{errorCode}, error_reason = #{errorReason}, "
            + "last_reported_at = #{lastReportedAt}, updated_by = #{updatedBy}, updated_at = CURRENT_TIMESTAMP, "
            + "row_version = row_version + 1 WHERE id = #{id} AND row_version = #{rowVersion} "
            + "AND last_event_sequence < #{lastEventSequence}")
    int updateFromEvent(RuntimeIntegrationInstanceEntity entity);
}
