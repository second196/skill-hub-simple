package com.km.skillhub.version.mapper;

import com.km.skillhub.version.model.entity.SkillVersionTagEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

public interface SkillVersionTagMapper {
    @Select("SELECT id, asset_id, tag_name, version_digest, created_by FROM skill_version_tag ORDER BY tag_name, asset_id")
    java.util.List<SkillVersionTagEntity> findAll();

    @Select("SELECT id, asset_id, tag_name, version_digest, created_by FROM skill_version_tag "
            + "WHERE asset_id = #{assetId} AND tag_name = #{tagName}")
    SkillVersionTagEntity find(@Param("assetId") Long assetId, @Param("tagName") String tagName);

    @Insert("INSERT INTO skill_version_tag(asset_id, tag_name, version_digest, created_by) "
            + "VALUES(#{assetId}, #{tagName}, #{versionDigest}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(SkillVersionTagEntity entity);

    @Update("UPDATE skill_version_tag SET version_digest = #{versionDigest}, created_by = #{createdBy} "
            + "WHERE asset_id = #{assetId} AND tag_name = #{tagName}")
    int update(SkillVersionTagEntity entity);

    @Insert("INSERT INTO skill_version_tag_history(asset_id, tag_name, previous_version_digest, "
            + "next_version_digest, changed_by) VALUES(#{assetId}, #{tagName}, #{previousDigest}, "
            + "#{nextDigest}, #{changedBy})")
    int insertHistory(@Param("assetId") Long assetId, @Param("tagName") String tagName,
                      @Param("previousDigest") String previousDigest, @Param("nextDigest") String nextDigest,
                      @Param("changedBy") String changedBy);

    @Delete("DELETE FROM skill_version_tag WHERE id = #{id}")
    int delete(@Param("id") Long id);
}
