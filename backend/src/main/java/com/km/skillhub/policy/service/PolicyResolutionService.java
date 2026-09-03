package com.km.skillhub.policy.service;

import com.km.skillhub.policy.mapper.ReleasePolicyMapper;
import com.km.skillhub.policy.model.entity.ReleasePolicyVersionEntity;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class PolicyResolutionService {
    private final ReleasePolicyMapper policyMapper;

    public PolicyResolutionService(ReleasePolicyMapper policyMapper) { this.policyMapper = policyMapper; }

    public ReleasePolicyVersionEntity resolve(String scopeType, Long scopeId) {
        ReleasePolicyVersionEntity policy = policyMapper.findEffectiveHierarchy(scopeType, scopeId);
        if (policy == null) policy = policyMapper.findEffective(scopeType, scopeId);
        if (policy != null) return policy;
        ReleasePolicyVersionEntity defaults = new ReleasePolicyVersionEntity();
        defaults.setScopeType(scopeType);
        defaults.setScopeId(scopeId);
        defaults.setPolicyVersion("default-v1");
        defaults.setEffectiveAt(OffsetDateTime.now());
        defaults.setMinimumValidCases(30);
        defaults.setGrayRatio(0.10D);
        defaults.setObservationWindowSeconds(86400L);
        defaults.setMinimumValidCalls(30);
        defaults.setRollbackConditions("{\"absoluteErrorRate\":0.02,\"relativeErrorRate\":0.20,\"scoreDrop\":0.05,\"highRisk\":true}");
        defaults.setAutoReleaseConditions("{\"risk\":\"LOW\",\"approvalRequired\":true}");
        return defaults;
    }
}
