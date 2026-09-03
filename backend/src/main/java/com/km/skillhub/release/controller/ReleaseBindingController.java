package com.km.skillhub.release.controller;

import com.km.skillhub.release.model.ReleaseTarget;
import com.km.skillhub.release.model.entity.ReleaseBindingEntity;
import com.km.skillhub.release.service.ReleaseScopeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/releases/bindings")
public class ReleaseBindingController {
    private final ReleaseScopeService releaseScopeService;

    public ReleaseBindingController(ReleaseScopeService releaseScopeService) {
        this.releaseScopeService = releaseScopeService;
    }

    @PostMapping
    public ResponseEntity<ReleaseBindingEntity> bind(@RequestBody ReleaseBindingRequest request,
                                                      Authentication authentication) {
        ReleaseBindingEntity result = releaseScopeService.bind(request.getAssetId(), request.getVersionDigest(),
                request.getTarget(), request.getPolicyVersion(), request.getDecisionId(), authentication.getName());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{assetId}")
    public ResponseEntity<ReleaseBindingEntity> current(@PathVariable Long assetId,
                                                         @RequestParam String scopeType,
                                                         @RequestParam Long scopeId) {
        ReleaseTarget target = new ReleaseTarget();
        target.setScopeType(scopeType);
        target.setScopeId(scopeId);
        return ResponseEntity.ok(releaseScopeService.current(assetId, target));
    }

    public static class ReleaseBindingRequest {
        private Long assetId;
        private String versionDigest;
        private ReleaseTarget target;
        private String policyVersion;
        private Long decisionId;
        public Long getAssetId() { return assetId; }
        public void setAssetId(Long assetId) { this.assetId = assetId; }
        public String getVersionDigest() { return versionDigest; }
        public void setVersionDigest(String versionDigest) { this.versionDigest = versionDigest; }
        public ReleaseTarget getTarget() { return target; }
        public void setTarget(ReleaseTarget target) { this.target = target; }
        public String getPolicyVersion() { return policyVersion; }
        public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
        public Long getDecisionId() { return decisionId; }
        public void setDecisionId(Long decisionId) { this.decisionId = decisionId; }
    }
}
