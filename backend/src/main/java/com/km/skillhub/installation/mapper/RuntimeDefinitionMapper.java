package com.km.skillhub.installation.mapper;

import com.km.skillhub.installation.model.entity.RuntimeDefinitionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RuntimeDefinitionMapper {
    @Select("SELECT id, runtime_key, runtime_version, status, capabilities::text AS capabilities "
            + "FROM runtime_definition WHERE runtime_key = #{runtimeKey} AND runtime_version = #{runtimeVersion}")
    RuntimeDefinitionEntity find(@Param("runtimeKey") String runtimeKey, @Param("runtimeVersion") String runtimeVersion);

    @Select("SELECT id, runtime_key, runtime_version, status, capabilities::text AS capabilities "
            + "FROM runtime_definition WHERE status = 'ACTIVE' ORDER BY runtime_key, runtime_version")
    List<RuntimeDefinitionEntity> findActive();
}
