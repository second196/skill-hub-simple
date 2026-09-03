package com.km.skillhub.mapper.content;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.km.skillhub.content.model.entity.SkillVersionFileEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SkillVersionFileMapper extends BaseMapper<SkillVersionFileEntity> {

    @Insert("INSERT INTO skill_version_manifest(version_id, path, required, read_status, content_digest) "
            + "VALUES(#{versionId}, #{path}, #{required}, #{readStatus}, #{contentDigest})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertFile(SkillVersionFileEntity entity);

    @Select("SELECT svm.id, svm.version_id, svm.path, svm.required, svm.read_status, svm.content_digest "
            + "FROM skill_version_manifest svm JOIN skill_version sv ON sv.id = svm.version_id "
            + "WHERE sv.version_digest = #{versionDigest} ORDER BY svm.path")
    List<SkillVersionFileEntity> findByVersionDigest(@Param("versionDigest") String versionDigest);
}
