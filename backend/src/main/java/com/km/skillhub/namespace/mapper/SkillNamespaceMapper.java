package com.km.skillhub.namespace.mapper;

import com.km.skillhub.namespace.model.entity.SkillNamespaceEntity;
import com.km.skillhub.namespace.model.vo.NamespaceMemberVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

public interface SkillNamespaceMapper {

    @Select("SELECT id, namespace_key, display_name, owner_scope_id, status FROM skill_namespace "
            + "WHERE namespace_key = #{namespaceKey} AND status = 'ACTIVE'")
    SkillNamespaceEntity findActive(@Param("namespaceKey") String namespaceKey);

    @Select("SELECT sn.id, sn.namespace_key, sn.display_name, sn.owner_scope_id, sn.status "
            + "FROM skill_namespace sn "
            + "WHERE sn.status = 'ACTIVE' "
            + "AND EXISTS (SELECT 1 FROM principal_account pa "
            + "JOIN principal_scope_role psr ON psr.principal_id = pa.id "
            + "WHERE pa.username = #{username} AND pa.enabled = TRUE "
            + "AND psr.scope_id = sn.owner_scope_id) "
            + "ORDER BY sn.namespace_key")
    List<SkillNamespaceEntity> findAuthorized(@Param("username") String username);

    @Select("SELECT p.id AS principal_id, p.username, m.role_key "
            + "FROM skill_namespace_member m JOIN principal_account p ON p.id = m.principal_id "
            + "JOIN skill_namespace sn ON sn.id = m.namespace_id "
            + "WHERE sn.namespace_key = #{namespaceKey} AND sn.status = 'ACTIVE' "
            + "AND EXISTS (SELECT 1 FROM principal_scope_role psr "
            + "JOIN principal_account actor ON actor.id = psr.principal_id "
            + "WHERE actor.username = #{username} AND actor.enabled = TRUE "
            + "AND psr.scope_id = sn.owner_scope_id) "
            + "ORDER BY m.role_key, p.username")
    List<NamespaceMemberVO> findMembers(@Param("namespaceKey") String namespaceKey,
                                        @Param("username") String username);

    @Select("SELECT id, namespace_key, display_name, owner_scope_id, status FROM skill_namespace "
            + "ORDER BY namespace_key")
    List<SkillNamespaceEntity> findAll();

    @Insert("INSERT INTO skill_namespace_member(namespace_id, principal_id, role_key, created_by) "
            + "SELECT id, (SELECT id FROM principal_account WHERE username = #{username}), #{roleKey}, #{createdBy} "
            + "FROM skill_namespace WHERE namespace_key = #{namespaceKey} AND status = 'ACTIVE'")
    int addMember(@Param("namespaceKey") String namespaceKey, @Param("username") String username,
                  @Param("roleKey") String roleKey, @Param("createdBy") String createdBy);

    @Update("UPDATE skill_namespace_member SET role_key = #{roleKey} WHERE principal_id = #{principalId}")
    int updateMemberRole(@Param("principalId") Long principalId, @Param("roleKey") String roleKey);

    @Delete("DELETE FROM skill_namespace_member WHERE principal_id = #{principalId}")
    int removeMember(@Param("principalId") Long principalId);

    @Select("SELECT p.id AS principal_id, p.username, m.role_key "
            + "FROM skill_namespace_member m JOIN principal_account p ON p.id = m.principal_id "
            + "JOIN skill_namespace sn ON sn.id = m.namespace_id WHERE sn.namespace_key = #{namespaceKey} "
            + "ORDER BY m.role_key, p.username")
    List<NamespaceMemberVO> findAllMembers(@Param("namespaceKey") String namespaceKey);
}
