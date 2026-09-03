package com.km.skillhub.release.service;

import com.km.skillhub.integration.event.GovernanceEventPublisher;
import com.km.skillhub.release.mapper.ReleaseDecisionMapper;
import com.km.skillhub.release.model.entity.ReleaseDecisionEntity;
import com.km.skillhub.audit.service.AuditQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RollbackDecisionService {
    private final ReleaseDecisionMapper decisionMapper;
    private final GovernanceEventPublisher eventPublisher;
    private final AuditQueryService auditQueryService;
    public RollbackDecisionService(ReleaseDecisionMapper decisionMapper, GovernanceEventPublisher eventPublisher,
                                   AuditQueryService auditQueryService) {
        this.decisionMapper = decisionMapper; this.eventPublisher = eventPublisher; this.auditQueryService = auditQueryService;
    }
    @Transactional
    public ReleaseDecisionEntity rollback(Long decisionId, String actor, String reason) {
        if (decisionId == null || actor == null || actor.trim().isEmpty() || reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Rollback requires actor and reason");
        }
        if (decisionMapper.rollback(decisionId) != 1) throw new IllegalArgumentException("Decision cannot be rolled back");
        ReleaseDecisionEntity decision = decisionMapper.findById(decisionId);
        eventPublisher.publish("RELEASE_ROLLBACK_REQUESTED", "release_decision", String.valueOf(decisionId),
                new RollbackEvent(decision.getVersionDigest(), decisionId, actor, reason));
        auditQueryService.record(actor, "ROLLBACK_RELEASE", "RELEASE_DECISION", String.valueOf(decisionId), reason,
                "{\"state\":\"APPROVED\"}", "{\"state\":\"ROLLED_BACK\"}", decision.getScopeType(),
                decision.getScopeId(), decision.getPolicyVersion());
        return decision;
    }
    public static class RollbackEvent {
        private final String versionDigest; private final Long decisionId; private final String actor; private final String reason;
        public RollbackEvent(String versionDigest, Long decisionId, String actor, String reason) {
            this.versionDigest = versionDigest; this.decisionId = decisionId; this.actor = actor; this.reason = reason;
        }
        public String getVersionDigest() { return versionDigest; }
        public Long getDecisionId() { return decisionId; }
        public String getActor() { return actor; }
        public String getReason() { return reason; }
    }
}
