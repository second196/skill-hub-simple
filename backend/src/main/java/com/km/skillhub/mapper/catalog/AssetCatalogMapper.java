package com.km.skillhub.mapper.catalog;

import com.km.skillhub.catalog.model.vo.AssetCatalogItemVO;
import com.km.skillhub.catalog.model.vo.AssetDetailVO;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AssetCatalogMapper {

    @Select("SELECT EXISTS (SELECT 1 FROM skill_version sv "
            + "JOIN skill_asset sa ON sa.id = sv.asset_id "
            + "WHERE sv.version_digest = #{versionDigest} AND sa.status = 'ACTIVE' "
            + "AND EXISTS (SELECT 1 FROM principal_account pa "
            + "JOIN principal_scope_role psr ON psr.principal_id = pa.id "
            + "WHERE pa.username = #{username} AND pa.enabled = TRUE "
            + "AND psr.scope_id = sa.owner_scope_id))")
    boolean hasVersionAccess(@Param("versionDigest") String versionDigest, @Param("username") String username);

    @Select({"<script>",
            "SELECT sa.id AS asset_id, sa.asset_key, sa.name, sa.description, sa.status,",
            "sv.version_label, sv.version_digest, sv.lifecycle_state, sv.metadata_status",
            "FROM skill_asset sa",
            "LEFT JOIN LATERAL (SELECT version_label, version_digest, lifecycle_state, metadata_status "
                    + "FROM skill_version WHERE asset_id = sa.id ORDER BY created_at DESC, id DESC LIMIT 1) sv ON TRUE",
            "WHERE sa.status = 'ACTIVE'",
            "AND EXISTS (SELECT 1 FROM principal_account pa "
                    + "JOIN principal_scope_role psr ON psr.principal_id = pa.id "
                    + "WHERE pa.username = #{username} AND pa.enabled = TRUE "
                    + "AND psr.scope_id = sa.owner_scope_id)",
            "<if test=\"keyword != null and keyword != ''\">",
            "AND (sa.name ILIKE CONCAT('%', #{keyword}, '%') OR sa.description ILIKE CONCAT('%', #{keyword}, '%')",
            "OR sa.asset_key ILIKE CONCAT('%', #{keyword}, '%'))",
            "</if>",
            "<if test=\"lifecycleState != null and lifecycleState != ''\">",
            "AND sv.lifecycle_state = #{lifecycleState}",
            "</if>",
            "ORDER BY sa.updated_at DESC, sa.id DESC",
            "LIMIT #{limit} OFFSET #{offset}",
            "</script>"})
    List<AssetCatalogItemVO> search(@Param("username") String username,
                                    @Param("keyword") String keyword,
                                    @Param("lifecycleState") String lifecycleState,
                                    @Param("limit") long limit,
                                    @Param("offset") long offset);

    @Select("SELECT sa.id AS asset_id, sa.asset_key, sa.name, sa.description, sa.owner_scope_id, sa.status, "
            + "sv.version_label, sv.version_digest, sv.lifecycle_state, sv.metadata_status "
            + "FROM skill_asset sa "
            + "LEFT JOIN LATERAL (SELECT version_label, version_digest, lifecycle_state, metadata_status "
            + "FROM skill_version WHERE asset_id = sa.id ORDER BY created_at DESC, id DESC LIMIT 1) sv ON TRUE "
            + "WHERE sa.id = #{assetId} AND sa.status = 'ACTIVE' "
            + "AND EXISTS (SELECT 1 FROM principal_account pa "
            + "JOIN principal_scope_role psr ON psr.principal_id = pa.id "
            + "WHERE pa.username = #{username} AND pa.enabled = TRUE "
            + "AND psr.scope_id = sa.owner_scope_id)")
    AssetDetailVO findDetail(@Param("assetId") Long assetId, @Param("username") String username);

    @Select("SELECT sv.id, sv.asset_id, sv.artifact_id, sv.version_label, sv.version_digest, "
            + "sv.source_type, sv.source_locator, sv.source_snapshot_uri, sv.metadata_status, "
            + "sv.lifecycle_state, sv.created_by "
            + "FROM skill_version sv "
            + "JOIN skill_asset sa ON sa.id = sv.asset_id "
            + "WHERE sv.asset_id = #{assetId} AND sa.status = 'ACTIVE' "
            + "AND EXISTS (SELECT 1 FROM principal_account pa "
            + "JOIN principal_scope_role psr ON psr.principal_id = pa.id "
            + "WHERE pa.username = #{username} AND pa.enabled = TRUE "
            + "AND psr.scope_id = sa.owner_scope_id) "
            + "ORDER BY sv.created_at DESC, sv.id DESC")
    List<SkillVersionEntity> findVersions(@Param("assetId") Long assetId, @Param("username") String username);
}
