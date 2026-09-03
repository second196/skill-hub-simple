package com.km.skillhub.version.controller;

import com.km.skillhub.version.model.VersionTransitionCommand;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import com.km.skillhub.version.service.LifecycleService;
import com.km.skillhub.version.service.VersionComparisonService;
import com.km.skillhub.version.service.VersionTagService;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.version.model.vo.VersionCompareVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/assets")
public class SkillVersionController {

    private final LifecycleService lifecycleService;
    private final VersionTagService versionTagService;
    private final VersionComparisonService comparisonService;
    private final AuthorizationService authorizationService;
    private final SkillVersionMapper versionMapper;

    public SkillVersionController(LifecycleService lifecycleService, VersionTagService versionTagService,
                                  VersionComparisonService comparisonService, AuthorizationService authorizationService,
                                  SkillVersionMapper versionMapper) {
        this.lifecycleService = lifecycleService;
        this.versionTagService = versionTagService;
        this.comparisonService = comparisonService;
        this.authorizationService = authorizationService;
        this.versionMapper = versionMapper;
    }

    @PostMapping("/versions/{versionDigest}/transition")
    public ResponseEntity<SkillVersionEntity> transition(@PathVariable String versionDigest,
                                                         @RequestBody VersionTransitionCommand command,
                                                         Authentication authentication) {
        command.setVersionDigest(versionDigest);
        if ("PUBLISHED".equals(command.getTargetState())) {
            throw new IllegalArgumentException("发布必须通过发布门禁和审核流程");
        }
        requireReleaseManager(versionDigest, authentication.getName());
        return ResponseEntity.ok(lifecycleService.transition(command));
    }

    @GetMapping("/versions/{versionDigest}")
    public ResponseEntity<SkillVersionEntity> getVersion(@PathVariable String versionDigest,
                                                         Authentication authentication) {
        requireAssetAccess(versionDigest, authentication.getName());
        return ResponseEntity.of(Optional.ofNullable(lifecycleService.findByDigest(versionDigest)));
    }

    @PostMapping("/versions/{versionDigest}/tags/{tagName}")
    public ResponseEntity<Void> bindTag(@PathVariable String versionDigest, @PathVariable String tagName,
                                        Authentication authentication) {
        requireReleaseManager(versionDigest, authentication.getName());
        versionTagService.bind(versionDigest, tagName, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/compare")
    public ResponseEntity<VersionCompareVO> compare(@RequestParam String from, @RequestParam String to,
                                                   Authentication authentication) {
        return ResponseEntity.ok(comparisonService.compare(from, to, authentication.getName()));
    }

    private void requireReleaseManager(String versionDigest, String username) {
        Long scopeId = versionMapper.findOwnerScopeId(versionDigest);
        authorizationService.requireRole(username, scopeId, "RELEASE_MANAGER");
    }

    private void requireAssetAccess(String versionDigest, String username) {
        Long scopeId = versionMapper.findOwnerScopeId(versionDigest);
        authorizationService.requireOneOfRoles(username, scopeId, "ASSET_CONTRIBUTOR", "REVIEWER",
                "GOVERNANCE_ADMIN", "AUDITOR");
    }
}
