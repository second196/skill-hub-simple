package com.km.skillhub.release.service;

import com.km.skillhub.release.mapper.ReleaseBindingMapper;
import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.release.model.ReleaseTarget;
import com.km.skillhub.release.model.entity.ReleaseBindingEntity;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import com.km.skillhub.version.service.LifecycleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class ReleaseScopeService {
    private final ReleaseBindingMapper bindingMapper;
    private final LifecycleService lifecycleService;
    private final AuthorizationService authorizationService;
    private final AuditQueryService auditQueryService;

    public ReleaseScopeService(ReleaseBindingMapper bindingMapper, LifecycleService lifecycleService,
                               AuthorizationService authorizationService, AuditQueryService auditQueryService) {
        this.bindingMapper = bindingMapper; this.lifecycleService = lifecycleService;
        this.authorizationService = authorizationService; this.auditQueryService = auditQueryService;
    }

    @Transactional
    public ReleaseBindingEntity bind(Long assetId, String versionDigest, ReleaseTarget target,
                                     String policyVersion, Long decisionId, String actor) {
        validateTarget(target);
        if (assetId == null || blank(versionDigest) || blank(policyVersion) || blank(actor)) {
            throw new IllegalArgumentException("Release binding is incomplete");
        }
        authorizationService.requireRole(actor, target.getScopeId(), "RELEASE_PUBLISHER");
        SkillVersionEntity version = lifecycleService.findByDigest(versionDigest);
        if (version == null || !assetId.equals(version.getAssetId())
                || !"PUBLISHED".equals(version.getLifecycleState())) {
            throw new IllegalArgumentException("Only a published version can be bound");
        }
        bindingMapper.clearCurrent(assetId, target.getScopeType(), target.getScopeId());
        ReleaseBindingEntity binding = new ReleaseBindingEntity();
        binding.setAssetId(assetId);
        binding.setVersionDigest(versionDigest);
        binding.setScopeType(target.getScopeType());
        binding.setScopeId(target.getScopeId());
        binding.setBindingState("ACTIVE");
        binding.setCurrent(Boolean.TRUE);
        binding.setPolicyVersion(policyVersion);
        binding.setDecisionId(decisionId);
        binding.setEffectiveAt(OffsetDateTime.now());
        binding.setReleasedAt(OffsetDateTime.now());
        binding.setCreatedBy(actor);
        bindingMapper.insert(binding);
        auditQueryService.record(actor, "BIND_RELEASE", "RELEASE_BINDING", String.valueOf(binding.getId()),
                "bind published version", "{}", "{\"versionDigest\":\"" + versionDigest + "\"}",
                target.getScopeType(), target.getScopeId(), policyVersion);
        return binding;
    }

    public ReleaseBindingEntity current(Long assetId, ReleaseTarget target) {
        validateTarget(target);
        return bindingMapper.findCurrent(assetId, target.getScopeType(), target.getScopeId());
    }

    private void validateTarget(ReleaseTarget target) {
        if (target == null || blank(target.getScopeType()) || target.getScopeId() == null
                || !("COMPANY".equals(target.getScopeType()) || "PROJECT".equals(target.getScopeType())
                || "ENVIRONMENT".equals(target.getScopeType()))) {
            throw new IllegalArgumentException("Release scope is invalid");
        }
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
