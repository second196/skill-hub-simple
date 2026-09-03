package com.km.skillhub.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.km.skillhub.asset.model.entity.SkillArtifactEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SkillArtifactMapper extends BaseMapper<SkillArtifactEntity> {

    @Select("SELECT sa.id, sa.asset_id, sa.artifact_uri, sa.artifact_digest, sa.media_type, sa.size_bytes, "
            + "sa.source_snapshot_uri, sa.encryption_status, sa.created_by "
            + "FROM skill_artifact sa JOIN skill_version sv ON sv.artifact_id = sa.id "
            + "WHERE sv.version_digest = #{versionDigest}")
    SkillArtifactEntity findByVersionDigest(@Param("versionDigest") String versionDigest);

    @Insert("INSERT INTO skill_artifact(asset_id, artifact_uri, artifact_digest, media_type, size_bytes, "
            + "source_snapshot_uri, encryption_status, created_by) VALUES(#{assetId}, #{artifactUri}, "
            + "#{artifactDigest}, #{mediaType}, #{sizeBytes}, #{sourceSnapshotUri}, #{encryptionStatus}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertArtifact(SkillArtifactEntity entity);
}
