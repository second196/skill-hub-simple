package com.km.skillhub.token.mapper;

import com.km.skillhub.token.model.ApiTokenEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ApiTokenMapper {
    @Insert("INSERT INTO api_token(principal_id, name, token_prefix, token_hash, scope_json, expires_at, created_at) "
            + "VALUES(#{principalId}, #{name}, #{tokenPrefix}, #{tokenHash}, CAST(#{scopeJson} AS jsonb), "
            + "#{expiresAt}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ApiTokenEntity entity);

    @Select("SELECT id, principal_id, name, token_prefix, token_hash, scope_json::text AS scope_json, expires_at, "
            + "last_used_at, revoked_at, created_at FROM api_token WHERE id = #{id}")
    ApiTokenEntity findById(@Param("id") Long id);

    @Select("SELECT id, principal_id, name, token_prefix, token_hash, scope_json::text AS scope_json, expires_at, "
            + "last_used_at, revoked_at, created_at FROM api_token WHERE token_hash = #{tokenHash}")
    ApiTokenEntity findByHash(@Param("tokenHash") String tokenHash);

    @Select("SELECT id, principal_id, name, token_prefix, token_hash, scope_json::text AS scope_json, expires_at, "
            + "last_used_at, revoked_at, created_at FROM api_token WHERE principal_id = #{principalId} "
            + "ORDER BY created_at DESC, id DESC")
    List<ApiTokenEntity> findByPrincipalId(@Param("principalId") Long principalId);

    @Update("UPDATE api_token SET expires_at = #{expiresAt} WHERE id = #{id} AND principal_id = #{principalId} "
            + "AND revoked_at IS NULL")
    int updateExpiration(@Param("id") Long id, @Param("principalId") Long principalId,
                         @Param("expiresAt") OffsetDateTime expiresAt);

    @Update("UPDATE api_token SET revoked_at = #{revokedAt} WHERE id = #{id} AND principal_id = #{principalId} "
            + "AND revoked_at IS NULL")
    int revoke(@Param("id") Long id, @Param("principalId") Long principalId,
               @Param("revokedAt") OffsetDateTime revokedAt);

    @Update("UPDATE api_token SET last_used_at = #{lastUsedAt} WHERE id = #{id} AND revoked_at IS NULL")
    int updateLastUsedAt(@Param("id") Long id, @Param("lastUsedAt") OffsetDateTime lastUsedAt);
}
