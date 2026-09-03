package com.km.skillhub.policy.controller;

import com.km.skillhub.policy.mapper.ReleasePolicyMapper;
import com.km.skillhub.policy.model.entity.ReleasePolicyVersionEntity;
import com.km.skillhub.policy.service.PolicyResolutionService;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.audit.service.AuditQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/governance/policies")
public class PolicyController {
    private final ReleasePolicyMapper policyMapper;
    private final PolicyResolutionService resolutionService;
    private final AuthorizationService authorizationService;
    private final AuditQueryService auditQueryService;

    public PolicyController(ReleasePolicyMapper policyMapper, PolicyResolutionService resolutionService,
                            AuthorizationService authorizationService, AuditQueryService auditQueryService) {
        this.policyMapper = policyMapper; this.resolutionService = resolutionService;
        this.authorizationService = authorizationService; this.auditQueryService = auditQueryService;
    }

    @GetMapping
    public ResponseEntity<List<ReleasePolicyVersionEntity>> list() { return ResponseEntity.ok(policyMapper.findAll()); }

    @PostMapping
    public ResponseEntity<ReleasePolicyVersionEntity> create(@RequestBody ReleasePolicyVersionEntity request,
                                                              Authentication authentication) {
        validate(request);
        authorizationService.requireRole(authentication.getName(), request.getScopeId(), "POLICY_ADMIN");
        request.setCreatedBy(authentication.getName());
        policyMapper.insert(request);
        auditQueryService.record(authentication.getName(), "CREATE_POLICY", "RELEASE_POLICY",
                request.getPolicyVersion(), "create policy version", "{}", "{\"policyVersion\":\""
                        + request.getPolicyVersion() + "\"}", request.getScopeType(), request.getScopeId(), request.getPolicyVersion());
        return ResponseEntity.ok(request);
    }

    private void validate(ReleasePolicyVersionEntity request) {
        if (request == null || request.getScopeType() == null || request.getScopeId() == null
                || request.getPolicyVersion() == null || request.getEffectiveAt() == null
                || request.getGrayRatio() == null || request.getGrayRatio() < 0 || request.getGrayRatio() > 1
                || request.getMinimumValidCalls() == null || request.getMinimumValidCalls() < 0) {
            throw new IllegalArgumentException("Policy is invalid");
        }
    }
}
