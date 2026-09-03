package com.km.skillhub.installation.controller;

import com.km.skillhub.catalog.model.vo.PageResult;
import com.km.skillhub.installation.model.dto.InstallationRequest;
import com.km.skillhub.installation.model.dto.RecoveryRequest;
import com.km.skillhub.installation.model.dto.RevocationRequest;
import com.km.skillhub.installation.model.query.InstallationQuery;
import com.km.skillhub.installation.model.vo.InstallationInstanceVO;
import com.km.skillhub.installation.model.vo.InstallationOperationVO;
import com.km.skillhub.installation.service.InstallationOrchestrationService;
import com.km.skillhub.installation.service.InstallationRecoveryService;
import com.km.skillhub.installation.service.VersionSwitchService;
import com.km.skillhub.installation.service.EmergencyRevocationService;
import com.km.skillhub.installation.domain.RecoveryDecision;
import com.km.skillhub.installation.model.vo.RevocationResultVO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/installations")
public class InstallationController {
    private final InstallationOrchestrationService service;
    private final VersionSwitchService versionSwitchService;
    private final InstallationRecoveryService recoveryService;
    private final EmergencyRevocationService emergencyRevocationService;

    @org.springframework.beans.factory.annotation.Autowired
    public InstallationController(InstallationOrchestrationService service,
                                   VersionSwitchService versionSwitchService,
                                   InstallationRecoveryService recoveryService,
                                   EmergencyRevocationService emergencyRevocationService) {
        this.service = service; this.versionSwitchService = versionSwitchService; this.recoveryService = recoveryService;
        this.emergencyRevocationService = emergencyRevocationService;
    }

    public InstallationController(InstallationOrchestrationService service) {
        this(service, null, null, null);
    }

    @PostMapping("/operations")
    public ResponseEntity<InstallationOperationVO> request(@RequestBody InstallationRequest request,
                                                            @RequestHeader("X-Request-Id") String requestId,
                                                            Authentication authentication) {
        return ResponseEntity.ok(service.requestInstallation(request, requestId, authentication.getName()));
    }

    @GetMapping("/operations/{operationId}")
    public ResponseEntity<InstallationOperationVO> operation(@PathVariable String operationId,
                                                              Authentication authentication) {
        return ResponseEntity.ok(service.findOperation(operationId, authentication.getName()));
    }

    @GetMapping
    public ResponseEntity<PageResult<InstallationInstanceVO>> search(InstallationQuery query,
                                                                      Authentication authentication) {
        List<InstallationInstanceVO> items = service.search(query, authentication.getName());
        InstallationQuery actual = query == null ? new InstallationQuery() : query;
        return ResponseEntity.ok(new PageResult<InstallationInstanceVO>(items, actual.getPage(), actual.limit()));
    }

    @GetMapping("/{instanceId}")
    public ResponseEntity<InstallationInstanceVO> instance(@PathVariable Long instanceId,
                                                            Authentication authentication) {
        return ResponseEntity.ok(service.findInstance(instanceId, authentication.getName()));
    }

    @GetMapping("/{instanceId}/operations/latest")
    public ResponseEntity<InstallationOperationVO> latestOperation(@PathVariable Long instanceId,
                                                                    Authentication authentication) {
        return ResponseEntity.ok(service.findLatestOperation(instanceId, authentication.getName()));
    }

    @PostMapping("/{instanceId}/switch")
    public ResponseEntity<InstallationOperationVO> switchVersion(@PathVariable Long instanceId,
                                                                   @RequestBody InstallationRequest request,
                                                                   @RequestHeader("X-Request-Id") String requestId,
                                                                   Authentication authentication) {
        return ResponseEntity.ok(versionSwitchService.requestSwitch(instanceId, request, requestId,
                authentication.getName()));
    }

    @PostMapping("/rollback")
    public ResponseEntity<RecoveryDecision> rollback(@RequestBody RecoveryRequest request,
                                                      Authentication authentication) {
        return ResponseEntity.accepted().body(recoveryService.recover(request.getOperationId(),
                authentication.getName(), request.getReason()));
    }

    @PostMapping("/revocations")
    public ResponseEntity<RevocationResultVO> revoke(@RequestBody RevocationRequest request,
                                                      @RequestHeader("X-Request-Id") String requestId,
                                                      Authentication authentication) {
        return ResponseEntity.accepted().body(emergencyRevocationService.revoke(request, requestId,
                authentication.getName()));
    }
}
