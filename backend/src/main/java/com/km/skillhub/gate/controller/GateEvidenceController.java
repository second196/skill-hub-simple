package com.km.skillhub.gate.controller;

import com.km.skillhub.gate.model.GateEvidenceRequest;
import com.km.skillhub.gate.model.entity.GateEvidenceEntity;
import com.km.skillhub.gate.service.GateEvidenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/releases/evidence")
public class GateEvidenceController {
    private final GateEvidenceService evidenceService;
    public GateEvidenceController(GateEvidenceService evidenceService) { this.evidenceService = evidenceService; }
    @PostMapping
    public ResponseEntity<GateEvidenceEntity> create(@RequestBody GateEvidenceRequest request) {
        return ResponseEntity.ok(evidenceService.create(request));
    }
}
