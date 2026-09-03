package com.km.skillhub.governance.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AuthorizationMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM principal_account pa JOIN principal_scope_role psr "
            + "ON psr.principal_id = pa.id WHERE pa.username = #{username} AND pa.enabled = TRUE "
            + "AND psr.scope_id = #{scopeId} AND psr.role_key = #{roleKey})")
    boolean hasRole(@Param("username") String username, @Param("scopeId") Long scopeId,
                    @Param("roleKey") String roleKey);

    @Select("SELECT EXISTS(SELECT 1 FROM principal_account pa JOIN principal_scope_role psr "
            + "ON psr.principal_id = pa.id WHERE pa.username = #{username} AND pa.enabled = TRUE "
            + "AND psr.role_key = #{roleKey})")
    boolean hasAnyRole(@Param("username") String username, @Param("roleKey") String roleKey);
}
