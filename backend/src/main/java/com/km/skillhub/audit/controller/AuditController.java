package com.km.skillhub.audit.controller;

import com.km.skillhub.audit.model.AuditLogEntity;
import com.km.skillhub.audit.service.AuditQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audits")
public class AuditController {
    private final AuditQueryService auditQueryService;
    public AuditController(AuditQueryService auditQueryService) { this.auditQueryService = auditQueryService; }
    @GetMapping
    public ResponseEntity<List<AuditLogEntity>> recent() { return ResponseEntity.ok(auditQueryService.recent()); }
}
