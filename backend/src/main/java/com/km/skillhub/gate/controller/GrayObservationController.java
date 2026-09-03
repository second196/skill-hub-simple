package com.km.skillhub.gate.controller;

import com.km.skillhub.gate.model.GrayObservation;
import com.km.skillhub.gate.service.GrayObservationService;
import com.km.skillhub.policy.service.PolicyResolutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/releases/decisions")
public class GrayObservationController {
    private final GrayObservationService observationService;
    private final PolicyResolutionService policyService;
    public GrayObservationController(GrayObservationService observationService, PolicyResolutionService policyService) {
        this.observationService = observationService; this.policyService = policyService;
    }
    @PostMapping("/{decisionId}/gray-observation")
    public ResponseEntity<GrayObservationService.ObservationResult> observe(@PathVariable Long decisionId,
                                                                               @RequestBody GrayObservation request) {
        if (request == null || request.getVersionDigest() == null) throw new IllegalArgumentException("Observation is incomplete");
        return ResponseEntity.ok(observationService.evaluate(request, policyService.resolve("COMPANY", 0L)));
    }
}
