package com.km.skillhub.installation.service;

import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.installation.domain.InstallationStateMachine;
import com.km.skillhub.installation.domain.RecoveryDecision;
import com.km.skillhub.installation.mapper.InstallationInstanceMapper;
import com.km.skillhub.installation.mapper.InstallationOperationMapper;
import com.km.skillhub.installation.model.InstallationState;
import com.km.skillhub.installation.model.entity.InstallationInstanceEntity;
import com.km.skillhub.installation.model.entity.InstallationOperationEntity;
import com.km.skillhub.integration.artifact.ArtifactAccessDescriptor;
import com.km.skillhub.integration.artifact.ArtifactAccessResolver;
import com.km.skillhub.integration.event.InstallationCommandPublisher;
import com.km.skillhub.integration.runtime.InstallationCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InstallationRecoveryService {
    private final InstallationOperationMapper operationMapper;
    private final InstallationInstanceMapper instanceMapper;
    private final AuthorizationService authorizationService;
    private final InstallationCommandPublisher commandPublisher;
    private final ArtifactAccessResolver artifactAccessResolver;

    public InstallationRecoveryService(InstallationOperationMapper operationMapper,
                                       InstallationInstanceMapper instanceMapper,
                                       AuthorizationService authorizationService,
                                       InstallationCommandPublisher commandPublisher,
                                       ArtifactAccessResolver artifactAccessResolver) {
        this.operationMapper = operationMapper;
        this.instanceMapper = instanceMapper;
        this.authorizationService = authorizationService;
        this.commandPublisher = commandPublisher;
        this.artifactAccessResolver = artifactAccessResolver;
    }

    @Transactional
    public RecoveryDecision recover(String operationId, String actor, String reason) {
        if (blank(operationId) || blank(actor)) throw new IllegalArgumentException("Recovery request is incomplete");
        InstallationOperationEntity operation = operationMapper.findByOperationId(operationId);
        if (operation == null) throw new IllegalArgumentException("Installation operation not found");
        authorizationService.requireRole(actor, operation.getScopeId(), "RELEASE_PUBLISHER");
        InstallationInstanceEntity instance = instanceMapper.findById(operation.getInstallationInstanceId());
        if (instance == null) throw new IllegalArgumentException("Installation instance not found");
        if ("SUCCEEDED".equals(operation.getOperationState()) || "ROLLED_BACK".equals(operation.getOperationState())) {
            return new RecoveryDecision(operationId, "NO_ACTION", operation.getPreviousVersionDigest(), "ALREADY_TERMINAL");
        }
        if (blank(operation.getPreviousVersionDigest())) {
            operation.setOperationState(InstallationState.REQUIRES_MANUAL.name());
            operation.setOperationStage(InstallationState.REQUIRES_MANUAL.name());
            operation.setRollbackState("NOT_POSSIBLE");
            operation.setErrorCode("NO_PREVIOUS_VERSION");
            operation.setErrorReason(reason == null ? "No previous version is available" : reason);
            operationMapper.updateProgress(operation);
            return new RecoveryDecision(operationId, "MANUAL_REQUIRED", null, InstallationState.REQUIRES_MANUAL.name());
        }
        InstallationStateMachine.transition(parse(operation.getOperationState()), InstallationState.ROLLING_BACK);
        operation.setOperationState(InstallationState.ROLLING_BACK.name());
        operation.setOperationStage(InstallationState.ROLLING_BACK.name());
        operation.setRollbackState("REQUESTED");
        operation.setErrorReason(reason);
        operationMapper.updateProgress(operation);
        instance.setOverallState(InstallationState.ROLLING_BACK.name());
        instance.setUpdatedBy(actor);
        instance.setRowVersion(instance.getRowVersion() == null ? 0L : instance.getRowVersion());
        instanceMapper.updateProgress(instance);
        ArtifactAccessDescriptor artifact = artifactAccessResolver.describe(
                "artifact://" + operation.getPreviousVersionDigest(), operation.getPreviousVersionDigest());
        commandPublisher.publish(new InstallationCommand(UUID.randomUUID().toString(), operationId, "ROLLBACK_REQUESTED",
                instance.getId(), operation.getAssetId(), operation.getPreviousVersionDigest(),
                operation.getTargetVersionDigest(), operation.getRuntimeKey(), operation.getRuntimeVersion(),
                operation.getScopeId(), artifact));
        return new RecoveryDecision(operationId, "ROLLBACK_REQUESTED", operation.getPreviousVersionDigest(),
                InstallationState.ROLLING_BACK.name());
    }

    private InstallationState parse(String value) {
        try { return InstallationState.valueOf(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Installation operation state is invalid"); }
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
