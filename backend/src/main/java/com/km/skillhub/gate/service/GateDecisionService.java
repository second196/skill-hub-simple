package com.km.skillhub.gate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.gate.domain.GateEvidenceSnapshot;
import com.km.skillhub.gate.domain.GateRuleEvaluator;
import com.km.skillhub.gate.mapper.GateEvidenceMapper;
import com.km.skillhub.gate.model.entity.GateEvidenceEntity;
import com.km.skillhub.policy.model.entity.ReleasePolicyVersionEntity;
import com.km.skillhub.policy.service.PolicyResolutionService;
import com.km.skillhub.release.mapper.ReleaseDecisionMapper;
import com.km.skillhub.release.model.ReleaseDecisionRequest;
import com.km.skillhub.release.model.entity.ReleaseDecisionEntity;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import com.km.skillhub.version.service.LifecycleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class GateDecisionService {
    private final GateEvidenceMapper evidenceMapper;
    private final ReleaseDecisionMapper decisionMapper;
    private final PolicyResolutionService policyService;
    private final LifecycleService lifecycleService;
    private final ObjectMapper objectMapper;
    private final GateRuleEvaluator evaluator = new GateRuleEvaluator();

    public GateDecisionService(GateEvidenceMapper evidenceMapper, ReleaseDecisionMapper decisionMapper,
                               PolicyResolutionService policyService, LifecycleService lifecycleService,
                               ObjectMapper objectMapper) {
        this.evidenceMapper = evidenceMapper; this.decisionMapper = decisionMapper;
        this.policyService = policyService; this.lifecycleService = lifecycleService; this.objectMapper = objectMapper;
    }

    @Transactional
    public ReleaseDecisionEntity decide(ReleaseDecisionRequest request, String actor) {
        if (request == null || blank(request.getVersionDigest()) || blank(request.getScopeType())
                || request.getScopeId() == null || blank(request.getReleaseMode()) || blank(request.getRequestId())
                || request.getEvidenceIds() == null || request.getEvidenceIds().isEmpty()) {
            throw new IllegalArgumentException("Release decision is incomplete");
        }
        SkillVersionEntity version = lifecycleService.findByDigest(request.getVersionDigest());
        if (version == null || !"CANDIDATE".equals(version.getLifecycleState())) {
            throw new IllegalArgumentException("Only a candidate version can enter release governance");
        }
        List<GateEvidenceEntity> entities = evidenceMapper.findByIds(request.getVersionDigest(), request.getEvidenceIds());
        if (entities == null || entities.size() != request.getEvidenceIds().size()) {
            throw new IllegalArgumentException("Evidence does not belong to the version");
        }
        ReleasePolicyVersionEntity policy = policyService.resolve(request.getScopeType(), request.getScopeId());
        GateRuleEvaluator.Result result = evaluator.evaluate(toSnapshots(entities), policy, request.getReleaseMode());
        ReleaseDecisionEntity decision = new ReleaseDecisionEntity();
        decision.setVersionDigest(request.getVersionDigest()); decision.setScopeType(request.getScopeType());
        decision.setScopeId(request.getScopeId()); decision.setPolicyVersion(policy.getPolicyVersion());
        decision.setReleaseMode(request.getReleaseMode()); decision.setDecisionState(result.getState());
        decision.setMatchedRules("[\"static_scan\",\"evaluation\",\"risk\",\"review\",\"runtime_compatibility\"]");
        decision.setBlockingReasons(toJson(result.getReasons())); decision.setRequestId(request.getRequestId());
        decision.setDecidedAt(OffsetDateTime.now()); decision.setCreatedBy(actor);
        decisionMapper.insert(decision);
        if ("APPROVED".equals(result.getState())) {
            com.km.skillhub.version.model.VersionTransitionCommand transition = new com.km.skillhub.version.model.VersionTransitionCommand();
            transition.setVersionDigest(request.getVersionDigest());
            transition.setTargetState("PUBLISHED");
            transition.setReason("Automatic release passed policy " + policy.getPolicyVersion());
            lifecycleService.transition(transition);
        }
        return decision;
    }

    public ReleaseDecisionEntity find(Long id) {
        if (id == null) throw new IllegalArgumentException("Decision ID is required");
        ReleaseDecisionEntity result = decisionMapper.findById(id);
        if (result == null) throw new IllegalArgumentException("Release decision not found");
        return result;
    }

    private List<GateEvidenceSnapshot> toSnapshots(List<GateEvidenceEntity> entities) {
        List<GateEvidenceSnapshot> result = new ArrayList<GateEvidenceSnapshot>();
        OffsetDateTime now = OffsetDateTime.now();
        for (GateEvidenceEntity entity : entities) {
            JsonNode node;
            try { node = objectMapper.readTree(entity.getConditions() == null ? "{}" : entity.getConditions()); }
            catch (Exception exception) { throw new IllegalArgumentException("Evidence conditions are invalid"); }
            result.add(new GateEvidenceSnapshot(entity.getEvidenceType(), entity.getResult(), integer(node, "validCases"),
                    integer(node, "validCalls"), decimal(node, "errorRate"), decimal(node, "baselineErrorRate"),
                    decimal(node, "score"), decimal(node, "baselineScore"), bool(node, "highRisk"),
                    bool(node, "unauthorizedChange"), bool(node, "runtimeCompatible", true),
                    bool(node, "regressionPassed", true), entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(now)));
        }
        return result;
    }
    private int integer(JsonNode node, String name) { return node.path(name).isNumber() ? node.path(name).asInt() : 0; }
    private double decimal(JsonNode node, String name) { return node.path(name).isNumber() ? node.path(name).asDouble() : 0D; }
    private boolean bool(JsonNode node, String name) { return bool(node, name, false); }
    private boolean bool(JsonNode node, String name, boolean defaultValue) { return node.path(name).isBoolean() ? node.path(name).asBoolean() : defaultValue; }
    private String toJson(List<String> values) {
        try { return objectMapper.writeValueAsString(values == null ? Collections.<String>emptyList() : values); }
        catch (Exception exception) { return "[]"; }
    }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
