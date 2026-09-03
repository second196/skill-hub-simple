package com.km.skillhub.installation.service;

import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.installation.mapper.InstallationInstanceMapper;
import com.km.skillhub.installation.model.dto.InstallationRequest;
import com.km.skillhub.installation.model.entity.InstallationInstanceEntity;
import com.km.skillhub.installation.model.vo.InstallationOperationVO;
import org.springframework.stereotype.Service;

@Service
public class VersionSwitchService {
    private final InstallationInstanceMapper instanceMapper;
    private final InstallationOrchestrationService orchestrationService;
    private final AuthorizationService authorizationService;

    public VersionSwitchService(InstallationInstanceMapper instanceMapper,
                                InstallationOrchestrationService orchestrationService,
                                AuthorizationService authorizationService) {
        this.instanceMapper = instanceMapper;
        this.orchestrationService = orchestrationService;
        this.authorizationService = authorizationService;
    }

    public InstallationOperationVO requestSwitch(Long instanceId, InstallationRequest request,
                                                  String requestId, String actor) {
        if (instanceId == null || request == null || request.getScopeId() == null) {
            throw new IllegalArgumentException("Version switch request is incomplete");
        }
        InstallationInstanceEntity instance = instanceMapper.findById(instanceId);
        if (instance == null) throw new IllegalArgumentException("Installation instance not found");
        authorizationService.requireRole(actor, instance.getScopeId(), "RELEASE_PUBLISHER");
        if (!instanceId.equals(instance.getId()) || !instance.getScopeId().equals(request.getScopeId())
                || !instance.getAssetId().equals(request.getAssetId()) || !instance.getRuntimeKey().equals(request.getRuntimeKey())
                || !instance.getRuntimeVersion().equals(request.getRuntimeVersion())
                || !instance.getTargetType().equals(request.getTargetType())
                || !instance.getTargetKey().equals(request.getTargetKey())) {
            throw new IllegalArgumentException("Version switch target does not match installation instance");
        }
        if (request.getVersionDigest() != null && request.getVersionDigest().equals(instance.getCurrentVersionDigest())) {
            throw new IllegalArgumentException("Target version is already active");
        }
        return orchestrationService.requestInstallation(request, requestId, actor);
    }
}
