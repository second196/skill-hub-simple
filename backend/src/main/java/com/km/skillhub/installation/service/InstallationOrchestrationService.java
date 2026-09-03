package com.km.skillhub.installation.service;

import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.integration.artifact.ArtifactAccessDescriptor;
import com.km.skillhub.integration.artifact.ArtifactAccessResolver;
import com.km.skillhub.integration.event.InstallationCommandPublisher;
import com.km.skillhub.integration.runtime.InstallationCommand;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.installation.mapper.InstallationInstanceMapper;
import com.km.skillhub.installation.mapper.InstallationOperationMapper;
import com.km.skillhub.installation.model.OperationType;
import com.km.skillhub.installation.model.InstallationState;
import com.km.skillhub.installation.model.dto.InstallationRequest;
import com.km.skillhub.installation.model.entity.InstallationInstanceEntity;
import com.km.skillhub.installation.model.entity.InstallationOperationEntity;
import com.km.skillhub.installation.model.query.InstallationQuery;
import com.km.skillhub.installation.model.vo.InstallationInstanceVO;
import com.km.skillhub.installation.model.vo.InstallationOperationVO;
import com.km.skillhub.installation.model.vo.RuntimeDefinitionVO;
import com.km.skillhub.release.mapper.ReleaseBindingMapper;
import com.km.skillhub.release.model.entity.ReleaseBindingEntity;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import com.km.skillhub.version.service.LifecycleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class InstallationOrchestrationService {
    private final InstallationInstanceMapper instanceMapper;
    private final InstallationOperationMapper operationMapper;
    private final ReleaseBindingMapper releaseBindingMapper;
    private final LifecycleService lifecycleService;
    private final AuthorizationService authorizationService;
    private final RuntimeMatrixService runtimeMatrixService;
    private final AuditQueryService auditQueryService;
    private final TrackerBindingService trackerBindingService;
    private final InstallationCommandPublisher commandPublisher;
    private final ArtifactAccessResolver artifactAccessResolver;

    @org.springframework.beans.factory.annotation.Autowired
    public InstallationOrchestrationService(InstallationInstanceMapper instanceMapper,
                                            InstallationOperationMapper operationMapper,
                                            ReleaseBindingMapper releaseBindingMapper,
                                            LifecycleService lifecycleService,
                                            AuthorizationService authorizationService,
                                            RuntimeMatrixService runtimeMatrixService,
                                            AuditQueryService auditQueryService,
                                            TrackerBindingService trackerBindingService,
                                            InstallationCommandPublisher commandPublisher,
                                            ArtifactAccessResolver artifactAccessResolver) {
        this.instanceMapper = instanceMapper; this.operationMapper = operationMapper;
        this.releaseBindingMapper = releaseBindingMapper; this.lifecycleService = lifecycleService;
        this.authorizationService = authorizationService; this.runtimeMatrixService = runtimeMatrixService;
        this.auditQueryService = auditQueryService;
        this.trackerBindingService = trackerBindingService;
        this.commandPublisher = commandPublisher;
        this.artifactAccessResolver = artifactAccessResolver;
    }

    public InstallationOrchestrationService(InstallationInstanceMapper instanceMapper,
                                            InstallationOperationMapper operationMapper,
                                            ReleaseBindingMapper releaseBindingMapper,
                                            LifecycleService lifecycleService,
                                            AuthorizationService authorizationService,
                                            RuntimeMatrixService runtimeMatrixService,
                                            AuditQueryService auditQueryService) {
        this(instanceMapper, operationMapper, releaseBindingMapper, lifecycleService, authorizationService,
                runtimeMatrixService, auditQueryService, null, null, null);
    }

    @Transactional
    public InstallationOperationVO requestInstallation(InstallationRequest request, String requestId, String actor) {
        validate(request, requestId, actor);
        authorizationService.requireRole(actor, request.getScopeId(), "RELEASE_PUBLISHER");
        SkillVersionEntity version = lifecycleService.findByDigest(request.getVersionDigest());
        if (version == null || !request.getAssetId().equals(version.getAssetId())
                || !"PUBLISHED".equals(version.getLifecycleState())) {
            throw new IllegalArgumentException("Only a published Skill version can be installed");
        }
        ReleaseBindingEntity binding = releaseBindingMapper.findCurrent(request.getAssetId(), request.getScopeType(), request.getScopeId());
        if (binding == null || !Boolean.TRUE.equals(binding.getCurrent())
                || !"ACTIVE".equals(binding.getBindingState())
                || !request.getVersionDigest().equals(binding.getVersionDigest())) {
            throw new IllegalArgumentException("Skill version is not released to the target scope");
        }
        runtimeMatrixService.requireSkillInstallation(request.getVersionDigest(), request.getRuntimeKey(), request.getRuntimeVersion());

        InstallationInstanceEntity instance = instanceMapper.findByTarget(request.getAssetId(), request.getRuntimeKey(),
                request.getTargetType(), request.getTargetKey(), request.getScopeId());
        if (instance != null && (InstallationState.REVOKE_REQUESTED.name().equals(instance.getDesiredState())
                || InstallationState.REVOKED.name().equals(instance.getDesiredState()))) {
            throw new IllegalArgumentException("Installation target is revoked");
        }
        if (instance == null) {
            instance = newInstance(request, actor);
            instanceMapper.insert(instance);
        }
        InstallationOperationEntity existing = operationMapper.findByRequest(requestId, instance.getId());
        if (existing != null) return InstallationOperationVO.from(existing);

        InstallationOperationEntity operation = newOperation(request, instance, requestId, actor);
        operationMapper.insert(operation);
        auditQueryService.record(actor, "REQUEST_INSTALLATION", "INSTALLATION_OPERATION", operation.getOperationId(),
                "request runtime installation", "{}", "{\"targetVersionDigest\":\"" + request.getVersionDigest() + "\"}",
                request.getScopeType(), request.getScopeId(), binding.getPolicyVersion());
        if (trackerBindingService != null) {
            trackerBindingService.prepare(instance.getId(), trackerKey(request), trackerVersion(request),
                    request.getTrackerConfigurationDigest(), actor);
        }
        if (commandPublisher != null) {
            String artifactUri = version.getSourceSnapshotUri();
            if (artifactUri == null || artifactUri.trim().isEmpty()) {
                artifactUri = "artifact://" + request.getVersionDigest();
            }
            ArtifactAccessDescriptor artifact = artifactAccessResolver.describe(artifactUri, request.getVersionDigest());
            commandPublisher.publish(new InstallationCommand(UUID.randomUUID().toString(), operation.getOperationId(),
                    "INSTALL".equals(operation.getOperationType()) ? "INSTALL_REQUESTED" : "SWITCH_REQUESTED",
                    instance.getId(), request.getAssetId(), request.getVersionDigest(),
                    operation.getPreviousVersionDigest(), request.getRuntimeKey(), request.getRuntimeVersion(),
                    request.getScopeId(), artifact));
        }
        return InstallationOperationVO.from(operation);
    }

    public InstallationOperationVO findOperation(String operationId, String actor) {
        InstallationOperationEntity operation = operationMapper.findByOperationId(operationId);
        if (operation == null) throw new IllegalArgumentException("Installation operation not found");
        authorizationService.requireRole(actor, operation.getScopeId(), "ASSET_CONTRIBUTOR");
        return InstallationOperationVO.from(operation);
    }

    public InstallationInstanceVO findInstance(Long instanceId, String actor) {
        InstallationInstanceEntity instance = instanceMapper.findById(instanceId);
        if (instance == null) throw new IllegalArgumentException("Installation instance not found");
        authorizationService.requireRole(actor, instance.getScopeId(), "ASSET_CONTRIBUTOR");
        return InstallationInstanceVO.from(instance);
    }

    public InstallationOperationVO findLatestOperation(Long instanceId, String actor) {
        InstallationInstanceEntity instance = instanceMapper.findById(instanceId);
        if (instance == null) throw new IllegalArgumentException("Installation instance not found");
        authorizationService.requireRole(actor, instance.getScopeId(), "ASSET_CONTRIBUTOR");
        InstallationOperationEntity operation = operationMapper.findLatestByInstance(instanceId);
        return operation == null ? null : InstallationOperationVO.from(operation);
    }

    public List<InstallationInstanceVO> search(InstallationQuery query, String actor) {
        InstallationQuery actual = query == null ? new InstallationQuery() : query;
        if (actual.getScopeId() == null) throw new IllegalArgumentException("Scope is required for installation query");
        authorizationService.requireRole(actor, actual.getScopeId(), "ASSET_CONTRIBUTOR");
        List<InstallationInstanceEntity> entities = instanceMapper.findPage(actual.getScopeId(), actual.getAssetId(),
                actual.getRuntimeKey(), actual.limit(), actual.offset());
        if (entities == null) return Collections.emptyList();
        java.util.ArrayList<InstallationInstanceVO> result = new java.util.ArrayList<InstallationInstanceVO>();
        for (InstallationInstanceEntity entity : entities) result.add(InstallationInstanceVO.from(entity));
        return result;
    }

    public List<RuntimeDefinitionVO> activeRuntimes(String actor, Long scopeId) {
        authorizationService.requireRole(actor, scopeId, "ASSET_CONTRIBUTOR");
        return runtimeMatrixService.activeDefinitions();
    }

    private InstallationInstanceEntity newInstance(InstallationRequest request, String actor) {
        InstallationInstanceEntity instance = new InstallationInstanceEntity();
        instance.setAssetId(request.getAssetId()); instance.setRuntimeKey(request.getRuntimeKey());
        instance.setRuntimeVersion(request.getRuntimeVersion()); instance.setTargetType(request.getTargetType());
        instance.setTargetKey(request.getTargetKey()); instance.setScopeId(request.getScopeId());
        instance.setDesiredState("ACTIVE"); instance.setSkillState("NOT_INSTALLED");
        instance.setTrackerState("NOT_INSTALLED"); instance.setOverallState(InstallationState.UNAVAILABLE.name());
        instance.setHealthStatus("UNKNOWN"); instance.setCreatedBy(actor); instance.setUpdatedBy(actor);
        return instance;
    }

    private InstallationOperationEntity newOperation(InstallationRequest request, InstallationInstanceEntity instance,
                                                      String requestId, String actor) {
        InstallationOperationEntity operation = new InstallationOperationEntity();
        operation.setOperationId(UUID.randomUUID().toString()); operation.setRequestId(requestId);
        operation.setOperationType(instance.getCurrentVersionDigest() == null ? OperationType.INSTALL.name() : OperationType.SWITCH.name());
        operation.setInstallationInstanceId(instance.getId()); operation.setAssetId(request.getAssetId());
        operation.setPreviousVersionDigest(instance.getCurrentVersionDigest()); operation.setTargetVersionDigest(request.getVersionDigest());
        operation.setRuntimeKey(request.getRuntimeKey()); operation.setRuntimeVersion(request.getRuntimeVersion());
        operation.setScopeId(request.getScopeId()); operation.setOperationStage(InstallationState.REQUESTED.name());
        operation.setOperationState(InstallationState.REQUESTED.name()); operation.setRollbackState("NOT_REQUIRED");
        operation.setActorId(actor); operation.setRequestedAt(OffsetDateTime.now());
        return operation;
    }

    private void validate(InstallationRequest request, String requestId, String actor) {
        if (request == null || request.getAssetId() == null || blank(request.getVersionDigest())
                || !request.getVersionDigest().matches("[0-9a-fA-F]{64}") || blank(request.getScopeType())
                || request.getScopeId() == null || blank(request.getRuntimeKey()) || blank(request.getRuntimeVersion())
                || blank(request.getTargetType()) || blank(request.getTargetKey()) || blank(requestId) || blank(actor)) {
            throw new IllegalArgumentException("Installation request is incomplete");
        }
        if (!("COMPANY".equals(request.getScopeType()) || "PROJECT".equals(request.getScopeType())
                || "ENVIRONMENT".equals(request.getScopeType()))) {
            throw new IllegalArgumentException("Installation scope is invalid");
        }
    }

    private String trackerKey(InstallationRequest request) {
        return request.getTrackerKey() == null || request.getTrackerKey().trim().isEmpty()
                ? TrackerBindingService.DEFAULT_TRACKER_KEY : request.getTrackerKey();
    }

    private String trackerVersion(InstallationRequest request) {
        return request.getTrackerVersion() == null || request.getTrackerVersion().trim().isEmpty()
                ? "initial" : request.getTrackerVersion();
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
