package com.km.skillhub.installation.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SkillRuntimeCompatibilityMapper {
    @Select("SELECT src.support_status FROM skill_runtime_compatibility src "
            + "JOIN skill_version sv ON sv.id = src.version_id "
            + "JOIN runtime_definition rd ON rd.id = src.runtime_id "
            + "WHERE sv.version_digest = #{versionDigest} AND rd.runtime_key = #{runtimeKey} "
            + "AND rd.runtime_version = #{runtimeVersion}")
    String findSupportStatus(@Param("versionDigest") String versionDigest, @Param("runtimeKey") String runtimeKey,
                             @Param("runtimeVersion") String runtimeVersion);
}
