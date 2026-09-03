package com.km.skillhub.release.controller;

import com.km.skillhub.release.model.ReleaseDecisionRequest;
import com.km.skillhub.release.model.entity.ReleaseDecisionEntity;
import com.km.skillhub.gate.service.GateDecisionService;
import com.km.skillhub.governance.service.ApprovalSeparationService;
import com.km.skillhub.release.service.RollbackDecisionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/releases/decisions")
public class ReleaseDecisionController {
    private final GateDecisionService decisionService;
    private final ApprovalSeparationService approvalService;
    private final RollbackDecisionService rollbackService;
    public ReleaseDecisionController(GateDecisionService decisionService, ApprovalSeparationService approvalService,
                                     RollbackDecisionService rollbackService) {
        this.decisionService = decisionService; this.approvalService = approvalService; this.rollbackService = rollbackService;
    }
    @PostMapping
    public ResponseEntity<ReleaseDecisionEntity> decide(@RequestBody ReleaseDecisionRequest request,
                                                         Authentication authentication) {
        return ResponseEntity.ok(decisionService.decide(request, authentication.getName()));
    }
    @GetMapping("/{decisionId}")
    public ResponseEntity<ReleaseDecisionEntity> get(@PathVariable Long decisionId) {
        return ResponseEntity.ok(decisionService.find(decisionId));
    }

    @PostMapping("/{decisionId}/approve")
    public ResponseEntity<ReleaseDecisionEntity> approve(@PathVariable Long decisionId,
                                                         @RequestBody ApprovalRequest request,
                                                         Authentication authentication) {
        return ResponseEntity.ok(approvalService.approve(decisionId, request.getApplicantId(), authentication.getName(),
                request.getScopeId(), request.getComment()));
    }

    @PostMapping("/{decisionId}/rollback")
    public ResponseEntity<ReleaseDecisionEntity> rollback(@PathVariable Long decisionId,
                                                           @RequestBody RollbackRequest request,
                                                           Authentication authentication) {
        return ResponseEntity.ok(rollbackService.rollback(decisionId, authentication.getName(), request.getReason()));
    }

    public static class ApprovalRequest {
        private String applicantId; private Long scopeId; private String comment;
        public String getApplicantId() { return applicantId; }
        public void setApplicantId(String applicantId) { this.applicantId = applicantId; }
        public Long getScopeId() { return scopeId; }
        public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }
    public static class RollbackRequest {
        private String reason;
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
