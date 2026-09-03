package com.km.skillhub.policy.controller;

import com.km.skillhub.policy.model.entity.RetentionPolicyVersionEntity;
import com.km.skillhub.policy.service.RetentionPolicyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/governance/retention-policies")
public class RetentionPolicyController {
    private final RetentionPolicyService service;
    public RetentionPolicyController(RetentionPolicyService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<List<RetentionPolicyVersionEntity>> list() { return ResponseEntity.ok(service.list()); }
    @PostMapping
    public ResponseEntity<RetentionPolicyVersionEntity> create(@RequestBody RetentionPolicyVersionEntity request,
                                                                Authentication authentication) {
        return ResponseEntity.ok(service.create(request, authentication.getName()));
    }
}
