package com.km.skillhub.governance.service;

import com.km.skillhub.integration.event.GovernanceEventPublisher;
import com.km.skillhub.release.mapper.ReleaseDecisionMapper;
import com.km.skillhub.governance.mapper.ApprovalMapper;
import com.km.skillhub.version.model.VersionTransitionCommand;
import com.km.skillhub.version.service.LifecycleService;
import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.release.model.entity.ReleaseDecisionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class ApprovalSeparationService {
    private final ApprovalMapper approvalMapper;
    private final ReleaseDecisionMapper decisionMapper;
    private final AuthorizationService authorizationService;
    private final GovernanceEventPublisher eventPublisher;
    private final LifecycleService lifecycleService;
    private final AuditQueryService auditQueryService;

    public ApprovalSeparationService(ApprovalMapper approvalMapper, ReleaseDecisionMapper decisionMapper,
                                     AuthorizationService authorizationService, GovernanceEventPublisher eventPublisher,
                                     LifecycleService lifecycleService, AuditQueryService auditQueryService) {
        this.approvalMapper = approvalMapper; this.decisionMapper = decisionMapper;
        this.authorizationService = authorizationService; this.eventPublisher = eventPublisher;
        this.lifecycleService = lifecycleService;
        this.auditQueryService = auditQueryService;
    }

    @Transactional
    public ReleaseDecisionEntity approve(Long decisionId, String applicantId, String approverId,
                                         Long scopeId, String comment) {
        if (decisionId == null || blank(applicantId) || blank(approverId) || applicantId.equals(approverId)) {
            throw new IllegalArgumentException("Applicant and approver must be different");
        }
        authorizationService.requireRole(approverId, scopeId, "RELEASE_APPROVER");
        ReleaseDecisionEntity decision = decisionMapper.findById(decisionId);
        if (decision == null) throw new IllegalArgumentException("Release decision not found");
        int updated = decisionMapper.updateState(decisionId, "APPROVED");
        if (updated != 1) throw new IllegalArgumentException("Decision is not awaiting approval");
        approvalMapper.insert(decisionId, applicantId, approverId, comment);
        VersionTransitionCommand transition = new VersionTransitionCommand();
        transition.setVersionDigest(decision.getVersionDigest());
        transition.setTargetState("PUBLISHED");
        transition.setReason("Release decision approved by " + approverId);
        lifecycleService.transition(transition);
        decision.setDecisionState("APPROVED");
        eventPublisher.publish("RELEASE_APPROVED", "release_decision", String.valueOf(decisionId), decision);
        auditQueryService.record(approverId, "APPROVE_RELEASE", "RELEASE_DECISION", String.valueOf(decisionId),
                comment, "{\"state\":\"PENDING_APPROVAL\"}", "{\"state\":\"APPROVED\"}",
                decision.getScopeType(), decision.getScopeId(), decision.getPolicyVersion());
        return decision;
    }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

}
