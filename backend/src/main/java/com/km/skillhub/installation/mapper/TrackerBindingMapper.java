package com.km.skillhub.installation.mapper;

import com.km.skillhub.installation.model.entity.TrackerBindingEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TrackerBindingMapper {
    @Select("SELECT id, installation_instance_id, tracker_key, tracker_version, configuration_digest, "
            + "installation_state, health_state, last_health_at, last_error_code, last_error_reason, created_by, "
            + "updated_by, created_at, updated_at, row_version FROM tracker_binding "
            + "WHERE installation_instance_id = #{instanceId} AND tracker_key = #{trackerKey}")
    TrackerBindingEntity findByInstanceAndKey(@Param("instanceId") Long instanceId,
                                              @Param("trackerKey") String trackerKey);

    @Insert("INSERT INTO tracker_binding(installation_instance_id, tracker_key, tracker_version, configuration_digest, "
            + "installation_state, health_state, created_by, updated_by) VALUES(#{installationInstanceId}, #{trackerKey}, "
            + "#{trackerVersion}, #{configurationDigest}, #{installationState}, #{healthState}, #{createdBy}, #{updatedBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(TrackerBindingEntity entity);

    @Update("UPDATE tracker_binding SET tracker_version = #{trackerVersion}, configuration_digest = #{configurationDigest}, "
            + "updated_by = #{updatedBy}, row_version = row_version + 1 WHERE id = #{id} AND row_version = #{rowVersion}")
    int updateDefinition(TrackerBindingEntity entity);

    @Update("UPDATE tracker_binding SET installation_state = #{installationState}, health_state = #{healthState}, "
            + "last_health_at = #{lastHealthAt}, last_error_code = #{lastErrorCode}, last_error_reason = #{lastErrorReason}, "
            + "updated_by = #{updatedBy}, row_version = row_version + 1 WHERE id = #{id} AND row_version = #{rowVersion}")
    int updateProgress(TrackerBindingEntity entity);
}
