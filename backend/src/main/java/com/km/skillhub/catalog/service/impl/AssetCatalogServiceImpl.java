package com.km.skillhub.catalog.service.impl;

import com.km.skillhub.catalog.model.query.AssetCatalogQuery;
import com.km.skillhub.catalog.model.vo.AssetCatalogItemVO;
import com.km.skillhub.catalog.model.vo.AssetDetailVO;
import com.km.skillhub.catalog.model.vo.PageResult;
import com.km.skillhub.catalog.service.AssetCatalogService;
import com.km.skillhub.mapper.catalog.AssetCatalogMapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

import com.km.skillhub.version.model.entity.SkillVersionEntity;

@Service
public class AssetCatalogServiceImpl implements AssetCatalogService {

    private final AssetCatalogMapper catalogMapper;

    public AssetCatalogServiceImpl(AssetCatalogMapper catalogMapper) {
        this.catalogMapper = catalogMapper;
    }

    @Override
    public PageResult<AssetCatalogItemVO> search(AssetCatalogQuery query, String username) {
        AssetCatalogQuery actual = query == null ? new AssetCatalogQuery() : query;
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required");
        }
        List<AssetCatalogItemVO> items = catalogMapper.search(username, actual.getKeyword(),
                actual.getLifecycleState(), actual.limit(), actual.offset());
        return new PageResult<AssetCatalogItemVO>(items == null ? Collections.<AssetCatalogItemVO>emptyList() : items,
                actual.getPage(), actual.limit());
    }

    @Override
    public AssetDetailVO findDetail(Long assetId, String username) {
        requireIdentity(assetId, username);
        AssetDetailVO detail = catalogMapper.findDetail(assetId, username);
        if (detail == null) {
            throw new IllegalArgumentException("Asset not found");
        }
        return detail;
    }

    @Override
    public List<SkillVersionEntity> findVersions(Long assetId, String username) {
        requireIdentity(assetId, username);
        if (catalogMapper.findDetail(assetId, username) == null) {
            throw new IllegalArgumentException("Asset not found");
        }
        List<SkillVersionEntity> versions = catalogMapper.findVersions(assetId, username);
        return versions == null ? Collections.<SkillVersionEntity>emptyList() : versions;
    }

    private void requireIdentity(Long assetId, String username) {
        if (assetId == null || assetId <= 0 || username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Asset identity is required");
        }
    }
}
