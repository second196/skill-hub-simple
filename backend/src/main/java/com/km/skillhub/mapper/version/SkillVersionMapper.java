package com.km.skillhub.mapper.version;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SkillVersionMapper extends BaseMapper<SkillVersionEntity> {

    @Select("SELECT id, asset_id, artifact_id, version_label, version_digest, source_type, source_locator, "
            + "source_snapshot_uri, metadata_status, lifecycle_state, created_by FROM skill_version "
            + "WHERE version_digest = #{versionDigest}")
    SkillVersionEntity findByDigest(@Param("versionDigest") String versionDigest);

    @Select("SELECT sa.owner_scope_id FROM skill_version sv JOIN skill_asset sa ON sa.id = sv.asset_id "
            + "WHERE sv.version_digest = #{versionDigest}")
    Long findOwnerScopeId(@Param("versionDigest") String versionDigest);

    @Insert("INSERT INTO skill_version(asset_id, artifact_id, version_label, version_digest, source_type, "
            + "source_locator, source_snapshot_uri, metadata_status, lifecycle_state, created_by) "
            + "VALUES(#{assetId}, #{artifactId}, #{versionLabel}, #{versionDigest}, #{sourceType}, "
            + "#{sourceLocator}, #{sourceSnapshotUri}, #{metadataStatus}, #{lifecycleState}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertVersion(SkillVersionEntity entity);

    @Update("UPDATE skill_version SET lifecycle_state = #{lifecycleState} "
            + "WHERE version_digest = #{versionDigest} AND lifecycle_state = #{expectedState}")
    int updateLifecycleState(@Param("versionDigest") String versionDigest,
                             @Param("expectedState") String expectedState,
                             @Param("lifecycleState") String lifecycleState);
}
