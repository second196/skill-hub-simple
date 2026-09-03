package com.km.skillhub.discovery.controller;

import com.km.skillhub.catalog.model.vo.AssetDetailVO;
import com.km.skillhub.catalog.model.vo.PageResult;
import com.km.skillhub.discovery.model.query.SkillDiscoveryQuery;
import com.km.skillhub.discovery.model.vo.SkillDiscoveryItemVO;
import com.km.skillhub.discovery.service.SkillDiscoveryService;
import com.km.skillhub.namespace.model.entity.SkillNamespaceEntity;
import com.km.skillhub.namespace.model.vo.NamespaceMemberVO;
import com.km.skillhub.version.model.vo.VersionCompareVO;
import com.km.skillhub.version.service.VersionComparisonService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillDiscoveryController {
    private final SkillDiscoveryService discoveryService;
    private final VersionComparisonService comparisonService;

    public SkillDiscoveryController(SkillDiscoveryService discoveryService,
                                    VersionComparisonService comparisonService) {
        this.discoveryService = discoveryService;
        this.comparisonService = comparisonService;
    }

    @GetMapping("/search")
    public ResponseEntity<PageResult<SkillDiscoveryItemVO>> search(SkillDiscoveryQuery query,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(discoveryService.search(query, authentication.getName()));
    }

    @GetMapping("/{namespaceKey}/{slug}")
    public ResponseEntity<AssetDetailVO> detail(@PathVariable String namespaceKey,
                                                @PathVariable String slug,
                                                Authentication authentication) {
        return ResponseEntity.ok(discoveryService.detail(namespaceKey, slug, authentication.getName()));
    }

    @GetMapping("/{namespaceKey}/{slug}/versions/compare")
    public ResponseEntity<VersionCompareVO> compare(@PathVariable String namespaceKey,
                                                    @PathVariable String slug,
                                                    @org.springframework.web.bind.annotation.RequestParam String from,
                                                    @org.springframework.web.bind.annotation.RequestParam String to,
                                                    Authentication authentication) {
        discoveryService.detail(namespaceKey, slug, authentication.getName());
        return ResponseEntity.ok(comparisonService.compare(from, to, authentication.getName()));
    }

    @GetMapping("/namespaces")
    public ResponseEntity<List<SkillNamespaceEntity>> namespaces(Authentication authentication) {
        return ResponseEntity.ok(discoveryService.namespaces(authentication.getName()));
    }

    @GetMapping("/namespaces/{namespaceKey}/members")
    public ResponseEntity<List<NamespaceMemberVO>> members(@PathVariable String namespaceKey,
                                                           Authentication authentication) {
        return ResponseEntity.ok(discoveryService.members(namespaceKey, authentication.getName()));
    }
}
