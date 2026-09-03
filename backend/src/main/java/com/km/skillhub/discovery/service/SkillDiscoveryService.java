package com.km.skillhub.discovery.service;

import com.km.skillhub.catalog.model.vo.AssetDetailVO;
import com.km.skillhub.catalog.model.vo.PageResult;
import com.km.skillhub.discovery.model.query.SkillDiscoveryQuery;
import com.km.skillhub.discovery.model.vo.SkillDiscoveryItemVO;
import com.km.skillhub.namespace.model.entity.SkillNamespaceEntity;
import com.km.skillhub.namespace.model.vo.NamespaceMemberVO;

import java.util.List;

public interface SkillDiscoveryService {
    PageResult<SkillDiscoveryItemVO> search(SkillDiscoveryQuery query, String username);
    AssetDetailVO detail(String namespaceKey, String slug, String username);
    List<SkillNamespaceEntity> namespaces(String username);
    List<NamespaceMemberVO> members(String namespaceKey, String username);
}
