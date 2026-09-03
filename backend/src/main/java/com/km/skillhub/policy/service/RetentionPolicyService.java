package com.km.skillhub.policy.service;

import com.km.skillhub.policy.mapper.RetentionPolicyMapper;
import com.km.skillhub.policy.model.entity.RetentionPolicyVersionEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RetentionPolicyService {
    private final RetentionPolicyMapper mapper;
    public RetentionPolicyService(RetentionPolicyMapper mapper) { this.mapper = mapper; }
    public List<RetentionPolicyVersionEntity> list() {
        List<RetentionPolicyVersionEntity> result = mapper.findAll();
        if (result != null && !result.isEmpty()) return result;
        List<RetentionPolicyVersionEntity> defaults = new ArrayList<RetentionPolicyVersionEntity>();
        defaults.add(defaultRule("ARTIFACT", "PERMANENT", null));
        defaults.add(defaultRule("VERSION", "PERMANENT", null));
        defaults.add(defaultRule("EVALUATION_REPORT", "PERMANENT", null));
        defaults.add(defaultRule("RELEASE_DECISION", "PERMANENT", null));
        defaults.add(defaultRule("AUDIT", "PERMANENT", null));
        defaults.add(defaultRule("RAW_RUNTIME_EVENT", "DURATION", 31536000L));
        defaults.add(defaultRule("AGGREGATED_METRIC", "PERMANENT", null));
        return defaults;
    }
    public RetentionPolicyVersionEntity create(RetentionPolicyVersionEntity entity, String actor) {
        if (entity == null || entity.getScopeType() == null || entity.getScopeId() == null
                || entity.getDataClass() == null || entity.getPolicyVersion() == null
                || entity.getEffectiveAt() == null || entity.getApprovedBy() == null
                || actor == null || actor.equals(entity.getApprovedBy())) throw new IllegalArgumentException("Retention policy is invalid");
        if ("PERMANENT".equals(entity.getRetentionType())) entity.setRetentionSeconds(null);
        else if (!"DURATION".equals(entity.getRetentionType()) || entity.getRetentionSeconds() == null || entity.getRetentionSeconds() <= 0) {
            throw new IllegalArgumentException("Retention duration is invalid");
        }
        entity.setCreatedBy(actor); mapper.insert(entity); return entity;
    }
    private RetentionPolicyVersionEntity defaultRule(String dataClass, String type, Long seconds) {
        RetentionPolicyVersionEntity entity = new RetentionPolicyVersionEntity(); entity.setScopeType("COMPANY");
        entity.setScopeId(0L); entity.setDataClass(dataClass); entity.setRetentionType(type); entity.setRetentionSeconds(seconds);
        entity.setPolicyVersion("default-v1"); entity.setApprovedBy("system"); entity.setCreatedBy("system"); return entity;
    }
}
