package com.km.skillhub.installation.controller;

import com.km.skillhub.installation.service.InstallationProgressService;
import com.km.skillhub.integration.runtime.InstallationEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/installations/events")
public class InstallationEventController {
    private final InstallationProgressService progressService;

    public InstallationEventController(InstallationProgressService progressService) {
        this.progressService = progressService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody InstallationEvent event, Authentication authentication) {
        progressService.accept(event, authentication == null ? "runtime-adapter" : authentication.getName());
        return ResponseEntity.accepted().build();
    }
}
