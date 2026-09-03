package com.km.skillhub.discovery.mapper;

import com.km.skillhub.discovery.model.vo.SkillDiscoveryItemVO;
import com.km.skillhub.catalog.model.vo.AssetDetailVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SkillDiscoveryMapper {

    @Select({"<script>",
            "SELECT sa.id AS asset_id, sn.namespace_key, sa.asset_key, sa.name, sa.description, sa.status,",
            "sv.version_label, sv.version_digest, sv.lifecycle_state, sv.metadata_status",
            "FROM skill_asset sa JOIN skill_namespace sn ON sn.id = sa.namespace_id",
            "LEFT JOIN LATERAL (SELECT version_label, version_digest, lifecycle_state, metadata_status",
            "FROM skill_version WHERE asset_id = sa.id ORDER BY created_at DESC, id DESC LIMIT 1) sv ON TRUE",
            "WHERE sa.status = 'ACTIVE' AND sn.status = 'ACTIVE'",
            "AND EXISTS (SELECT 1 FROM principal_account pa "
                    + "JOIN principal_scope_role psr ON psr.principal_id = pa.id "
                    + "WHERE pa.username = #{username} AND pa.enabled = TRUE "
                    + "AND psr.scope_id = sa.owner_scope_id)",
            "<if test=\"keyword != null and keyword != ''\">",
            "AND (sa.name ILIKE CONCAT('%', #{keyword}, '%') OR sa.description ILIKE CONCAT('%', #{keyword}, '%')",
            "OR sa.asset_key ILIKE CONCAT('%', #{keyword}, '%') OR sn.namespace_key ILIKE CONCAT('%', #{keyword}, '%'))",
            "</if>",
            "<if test=\"namespaceKey != null and namespaceKey != ''\">AND sn.namespace_key = #{namespaceKey}</if>",
            "<if test=\"lifecycleState != null and lifecycleState != ''\">AND sv.lifecycle_state = #{lifecycleState}</if>",
            "<if test=\"tag != null and tag != ''\">",
            "AND EXISTS (SELECT 1 FROM skill_version_tag tag_row WHERE tag_row.asset_id = sa.id",
            "AND tag_row.tag_name = #{tag} AND tag_row.version_digest = sv.version_digest)",
            "</if>",
            "ORDER BY sa.updated_at DESC, sa.id DESC LIMIT #{limit} OFFSET #{offset}",
            "</script>"})
    List<SkillDiscoveryItemVO> search(@Param("username") String username,
                                      @Param("keyword") String keyword,
                                      @Param("namespaceKey") String namespaceKey,
                                      @Param("lifecycleState") String lifecycleState,
                                      @Param("tag") String tag,
                                      @Param("limit") long limit,
                                      @Param("offset") long offset);

    @Select("SELECT sa.id AS asset_id, sa.asset_key, sa.name, sa.description, sa.owner_scope_id, sa.status, "
            + "sv.version_label, sv.version_digest, sv.lifecycle_state, sv.metadata_status "
            + "FROM skill_asset sa JOIN skill_namespace sn ON sn.id = sa.namespace_id "
            + "LEFT JOIN LATERAL (SELECT version_label, version_digest, lifecycle_state, metadata_status "
            + "FROM skill_version WHERE asset_id = sa.id ORDER BY created_at DESC, id DESC LIMIT 1) sv ON TRUE "
            + "WHERE sn.namespace_key = #{namespaceKey} AND sa.asset_key = #{slug} AND sa.status = 'ACTIVE' "
            + "AND sn.status = 'ACTIVE' "
            + "AND EXISTS (SELECT 1 FROM principal_account pa "
            + "JOIN principal_scope_role psr ON psr.principal_id = pa.id "
            + "WHERE pa.username = #{username} AND pa.enabled = TRUE "
            + "AND psr.scope_id = sa.owner_scope_id)")
    AssetDetailVO findDetail(@Param("namespaceKey") String namespaceKey,
                             @Param("slug") String slug,
                             @Param("username") String username);
}
