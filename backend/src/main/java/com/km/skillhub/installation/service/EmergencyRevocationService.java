package com.km.skillhub.installation.service;

import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.installation.mapper.InstallationInstanceMapper;
import com.km.skillhub.installation.mapper.InstallationOperationMapper;
import com.km.skillhub.installation.model.InstallationState;
import com.km.skillhub.installation.model.OperationType;
import com.km.skillhub.installation.model.dto.RevocationRequest;
import com.km.skillhub.installation.model.entity.InstallationInstanceEntity;
import com.km.skillhub.installation.model.entity.InstallationOperationEntity;
import com.km.skillhub.installation.model.vo.RevocationResultVO;
import com.km.skillhub.integration.artifact.ArtifactAccessDescriptor;
import com.km.skillhub.integration.artifact.ArtifactAccessResolver;
import com.km.skillhub.integration.event.InstallationCommandPublisher;
import com.km.skillhub.integration.runtime.InstallationCommand;
import com.km.skillhub.release.mapper.ReleaseBindingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class EmergencyRevocationService {
    private final AuthorizationService authorizationService;
    private final ReleaseBindingMapper releaseBindingMapper;
    private final InstallationInstanceMapper instanceMapper;
    private final InstallationOperationMapper operationMapper;
    private final InstallationCommandPublisher commandPublisher;
    private final ArtifactAccessResolver artifactAccessResolver;

    public EmergencyRevocationService(AuthorizationService authorizationService,
                                      ReleaseBindingMapper releaseBindingMapper,
                                      InstallationInstanceMapper instanceMapper,
                                      InstallationOperationMapper operationMapper,
                                      InstallationCommandPublisher commandPublisher,
                                      ArtifactAccessResolver artifactAccessResolver) {
        this.authorizationService = authorizationService;
        this.releaseBindingMapper = releaseBindingMapper;
        this.instanceMapper = instanceMapper;
        this.operationMapper = operationMapper;
        this.commandPublisher = commandPublisher;
        this.artifactAccessResolver = artifactAccessResolver;
    }

    @Transactional
    public RevocationResultVO revoke(RevocationRequest request, String requestId, String actor) {
        validate(request, requestId, actor);
        authorizationService.requireRole(actor, request.getScopeId(), "GOVERNANCE_ADMIN");
        releaseBindingMapper.revokeCurrent(request.getVersionDigest(), request.getScopeType(), request.getScopeId());
        List<InstallationInstanceEntity> instances = instanceMapper.findByScopeAndVersion(request.getScopeId(),
                request.getVersionDigest());
        List<String> operationIds = new ArrayList<String>();
        for (InstallationInstanceEntity instance : instances) {
            operationIds.add(queueRevocation(instance, request, requestId, actor));
        }
        return new RevocationResultVO(request.getVersionDigest(), instances.size(), operationIds.size(), operationIds);
    }

    private String queueRevocation(InstallationInstanceEntity instance, RevocationRequest request,
                                   String requestId, String actor) {
        InstallationOperationEntity operation = new InstallationOperationEntity();
        operation.setOperationId(UUID.randomUUID().toString());
        operation.setRequestId(requestId + ":" + instance.getId());
        operation.setOperationType(OperationType.REVOKE.name());
        operation.setInstallationInstanceId(instance.getId()); operation.setAssetId(instance.getAssetId());
        operation.setPreviousVersionDigest(instance.getCurrentVersionDigest());
        operation.setTargetVersionDigest(instance.getCurrentVersionDigest());
        operation.setRuntimeKey(instance.getRuntimeKey()); operation.setRuntimeVersion(instance.getRuntimeVersion());
        operation.setScopeId(instance.getScopeId()); operation.setOperationStage(InstallationState.REVOKE_REQUESTED.name());
        operation.setOperationState(InstallationState.REVOKE_REQUESTED.name()); operation.setRollbackState("NOT_REQUIRED");
        operation.setActorId(actor); operationMapper.insert(operation);

        instance.setDesiredState(InstallationState.REVOKE_REQUESTED.name());
        instance.setOverallState(InstallationState.REVOKE_REQUESTED.name()); instance.setUpdatedBy(actor);
        instance.setRowVersion(instance.getRowVersion() == null ? 0L : instance.getRowVersion());
        instanceMapper.updateProgress(instance);
        ArtifactAccessDescriptor artifact = artifactAccessResolver.describe("artifact://" + instance.getCurrentVersionDigest(),
                instance.getCurrentVersionDigest());
        commandPublisher.publish(new InstallationCommand(UUID.randomUUID().toString(), operation.getOperationId(),
                "REVOKE_REQUESTED", instance.getId(), instance.getAssetId(), instance.getCurrentVersionDigest(),
                null, instance.getRuntimeKey(), instance.getRuntimeVersion(), instance.getScopeId(), artifact));
        return operation.getOperationId();
    }

    private void validate(RevocationRequest request, String requestId, String actor) {
        if (request == null || blank(request.getVersionDigest()) || !request.getVersionDigest().matches("[0-9a-fA-F]{64}")
                || blank(request.getScopeType()) || request.getScopeId() == null || blank(requestId) || blank(actor)) {
            throw new IllegalArgumentException("Revocation request is incomplete");
        }
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
