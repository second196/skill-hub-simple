package com.km.skillhub.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.km.skillhub.asset.model.entity.SkillImportAttemptEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SkillImportAttemptMapper extends BaseMapper<SkillImportAttemptEntity> {

    @Select("SELECT id, request_id, asset_id, source_type, source_locator, status, failure_stage, "
            + "failure_code, failure_reason, artifact_digest, created_by "
            + "FROM skill_import_attempt WHERE request_id = #{requestId}")
    SkillImportAttemptEntity findByRequestId(String requestId);

    @Insert("INSERT INTO skill_import_attempt(request_id, asset_id, source_type, source_locator, status, "
            + "failure_stage, failure_code, failure_reason, artifact_digest, created_by) "
            + "VALUES(#{requestId}, #{assetId}, #{sourceType}, #{sourceLocator}, #{status}, #{failureStage}, "
            + "#{failureCode}, #{failureReason}, #{artifactDigest}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertAttempt(SkillImportAttemptEntity entity);

    @Update("UPDATE skill_import_attempt SET asset_id = #{assetId}, status = #{status}, "
            + "failure_stage = #{failureStage}, failure_code = #{failureCode}, failure_reason = #{failureReason}, "
            + "artifact_digest = #{artifactDigest} WHERE request_id = #{requestId}")
    int updateResult(SkillImportAttemptEntity entity);
}
