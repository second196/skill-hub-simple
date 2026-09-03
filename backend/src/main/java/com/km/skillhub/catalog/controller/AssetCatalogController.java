package com.km.skillhub.catalog.controller;

import com.km.skillhub.catalog.model.query.AssetCatalogQuery;
import com.km.skillhub.catalog.model.vo.AssetCatalogItemVO;
import com.km.skillhub.catalog.model.vo.AssetDetailVO;
import com.km.skillhub.catalog.model.vo.PageResult;
import com.km.skillhub.catalog.service.AssetCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.km.skillhub.version.model.entity.SkillVersionEntity;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetCatalogController {

    private final AssetCatalogService assetCatalogService;

    public AssetCatalogController(AssetCatalogService assetCatalogService) {
        this.assetCatalogService = assetCatalogService;
    }

    @GetMapping
    public ResponseEntity<PageResult<AssetCatalogItemVO>> search(AssetCatalogQuery query,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(assetCatalogService.search(query, authentication.getName()));
    }

    @GetMapping("/{assetId}")
    public ResponseEntity<AssetDetailVO> detail(@PathVariable Long assetId,
                                                Authentication authentication) {
        return ResponseEntity.ok(assetCatalogService.findDetail(assetId, authentication.getName()));
    }

    @GetMapping("/{assetId}/versions")
    public ResponseEntity<List<SkillVersionEntity>> versions(@PathVariable Long assetId,
                                                             Authentication authentication) {
        return ResponseEntity.ok(assetCatalogService.findVersions(assetId, authentication.getName()));
    }
}
