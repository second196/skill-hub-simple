package com.km.skillhub.installation.controller;

import com.km.skillhub.installation.model.dto.RuntimeIntegrationEventRequest;
import com.km.skillhub.installation.model.dto.RuntimeIntegrationRegistration;
import com.km.skillhub.installation.model.vo.RuntimeIntegrationVO;
import com.km.skillhub.installation.service.RuntimeIntegrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/runtime-integrations")
public class RuntimeIntegrationController {
    private final RuntimeIntegrationService service;

    public RuntimeIntegrationController(RuntimeIntegrationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RuntimeIntegrationVO> register(@RequestBody RuntimeIntegrationRegistration request,
                                                          Authentication authentication) {
        return ResponseEntity.ok(service.register(request, authentication.getName()));
    }

    @PostMapping("/{integrationId}/events")
    public ResponseEntity<RuntimeIntegrationVO> event(@PathVariable String integrationId,
                                                       @RequestBody RuntimeIntegrationEventRequest request,
                                                       Authentication authentication) {
        return ResponseEntity.ok(service.acceptEvent(integrationId, request, authentication.getName()));
    }

    @GetMapping
    public ResponseEntity<List<RuntimeIntegrationVO>> list(@RequestParam(required = false) Long scopeId,
                                                            @RequestParam(required = false) String runtimeKey,
                                                            @RequestParam(required = false) Integer limit,
                                                            Authentication authentication) {
        return ResponseEntity.ok(service.list(scopeId, runtimeKey, limit, authentication.getName()));
    }

    @GetMapping("/{integrationId}")
    public ResponseEntity<RuntimeIntegrationVO> find(@PathVariable String integrationId,
                                                      Authentication authentication) {
        return ResponseEntity.ok(service.find(integrationId, authentication.getName()));
    }
}
