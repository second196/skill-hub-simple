package com.km.skillhub.catalog.service;

import com.km.skillhub.catalog.model.query.AssetCatalogQuery;
import com.km.skillhub.catalog.model.vo.AssetCatalogItemVO;
import com.km.skillhub.catalog.model.vo.AssetDetailVO;
import com.km.skillhub.catalog.model.vo.PageResult;

import com.km.skillhub.version.model.entity.SkillVersionEntity;

import java.util.List;

public interface AssetCatalogService {
    PageResult<AssetCatalogItemVO> search(AssetCatalogQuery query, String username);

    AssetDetailVO findDetail(Long assetId, String username);

    List<SkillVersionEntity> findVersions(Long assetId, String username);
}
