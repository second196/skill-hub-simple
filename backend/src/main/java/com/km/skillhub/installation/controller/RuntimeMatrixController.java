package com.km.skillhub.installation.controller;

import com.km.skillhub.installation.model.vo.RuntimeDefinitionVO;
import com.km.skillhub.installation.service.InstallationOrchestrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/runtime-matrix")
public class RuntimeMatrixController {
    private final InstallationOrchestrationService service;
    public RuntimeMatrixController(InstallationOrchestrationService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<List<RuntimeDefinitionVO>> active(@RequestParam Long scopeId,
                                                             Authentication authentication) {
        return ResponseEntity.ok(service.activeRuntimes(authentication.getName(), scopeId));
    }
}
