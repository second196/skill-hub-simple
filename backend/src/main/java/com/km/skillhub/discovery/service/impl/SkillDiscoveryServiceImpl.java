package com.km.skillhub.discovery.service.impl;

import com.km.skillhub.catalog.model.vo.AssetDetailVO;
import com.km.skillhub.catalog.model.vo.PageResult;
import com.km.skillhub.discovery.mapper.SkillDiscoveryMapper;
import com.km.skillhub.discovery.model.query.SkillDiscoveryQuery;
import com.km.skillhub.discovery.model.vo.SkillDiscoveryItemVO;
import com.km.skillhub.discovery.service.SkillDiscoveryService;
import com.km.skillhub.namespace.mapper.SkillNamespaceMapper;
import com.km.skillhub.namespace.model.entity.SkillNamespaceEntity;
import com.km.skillhub.namespace.model.vo.NamespaceMemberVO;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class SkillDiscoveryServiceImpl implements SkillDiscoveryService {
    private final SkillDiscoveryMapper discoveryMapper;
    private final SkillNamespaceMapper namespaceMapper;

    public SkillDiscoveryServiceImpl(SkillDiscoveryMapper discoveryMapper, SkillNamespaceMapper namespaceMapper) {
        this.discoveryMapper = discoveryMapper;
        this.namespaceMapper = namespaceMapper;
    }

    @Override
    public PageResult<SkillDiscoveryItemVO> search(SkillDiscoveryQuery query, String username) {
        SkillDiscoveryQuery actual = query == null ? new SkillDiscoveryQuery() : query;
        List<SkillDiscoveryItemVO> items = discoveryMapper.search(username, actual.getKeyword(),
                actual.getNamespaceKey(), actual.getLifecycleState(), actual.getTag(), actual.limit(), actual.offset());
        return new PageResult<SkillDiscoveryItemVO>(items == null ? Collections.<SkillDiscoveryItemVO>emptyList() : items,
                actual.getPage(), actual.limit());
    }

    @Override
    public AssetDetailVO detail(String namespaceKey, String slug, String username) {
        AssetDetailVO result = discoveryMapper.findDetail(namespaceKey, slug, username);
        if (result == null) {
            throw new IllegalArgumentException("Skill 不存在或无权访问");
        }
        return result;
    }

    @Override
    public List<SkillNamespaceEntity> namespaces(String username) {
        List<SkillNamespaceEntity> result = namespaceMapper.findAuthorized(username);
        return result == null ? Collections.<SkillNamespaceEntity>emptyList() : result;
    }

    @Override
    public List<NamespaceMemberVO> members(String namespaceKey, String username) {
        List<NamespaceMemberVO> result = namespaceMapper.findMembers(namespaceKey, username);
        return result == null ? Collections.<NamespaceMemberVO>emptyList() : result;
    }
}
