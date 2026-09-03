package com.km.skillhub.installation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.installation.domain.InstallationStateMachine;
import com.km.skillhub.installation.mapper.InstallationInstanceMapper;
import com.km.skillhub.installation.mapper.InstallationOperationEventMapper;
import com.km.skillhub.installation.mapper.InstallationOperationMapper;
import com.km.skillhub.installation.mapper.TrackerBindingMapper;
import com.km.skillhub.installation.model.InstallationState;
import com.km.skillhub.installation.model.OperationType;
import com.km.skillhub.installation.model.entity.InstallationInstanceEntity;
import com.km.skillhub.installation.model.entity.InstallationOperationEntity;
import com.km.skillhub.installation.model.entity.InstallationOperationEventEntity;
import com.km.skillhub.installation.model.entity.TrackerBindingEntity;
import com.km.skillhub.integration.runtime.InstallationEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class InstallationProgressService {
    private final InstallationOperationMapper operationMapper;
    private final InstallationOperationEventMapper eventMapper;
    private final InstallationInstanceMapper instanceMapper;
    private final TrackerBindingMapper trackerBindingMapper;
    private final InstallationEligibilityPolicy eligibilityPolicy;
    private final ObjectMapper objectMapper;

    public InstallationProgressService(InstallationOperationMapper operationMapper,
                                       InstallationOperationEventMapper eventMapper,
                                       InstallationInstanceMapper instanceMapper,
                                       TrackerBindingMapper trackerBindingMapper,
                                       InstallationEligibilityPolicy eligibilityPolicy,
                                       ObjectMapper objectMapper) {
        this.operationMapper = operationMapper;
        this.eventMapper = eventMapper;
        this.instanceMapper = instanceMapper;
        this.trackerBindingMapper = trackerBindingMapper;
        this.eligibilityPolicy = eligibilityPolicy;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void accept(InstallationEvent event, String actor) {
        if (event == null) {
            throw new IllegalArgumentException("Installation event is required");
        }
        event.validate();
        InstallationOperationEntity operation = operationMapper.findByOperationId(event.getOperationId());
        if (operation == null) {
            throw new IllegalArgumentException("Installation operation not found");
        }
        if (!operation.getInstallationInstanceId().equals(event.getInstanceId())
                || !operation.getAssetId().equals(event.getAssetId())
                || !operation.getRuntimeKey().equals(event.getRuntimeKey())
                || !operation.getRuntimeVersion().equals(event.getRuntimeVersion())
                || !operation.getScopeId().equals(event.getScopeId())
                || !versionMatches(operation, event)) {
            throw new IllegalArgumentException("Installation event does not match operation");
        }
        if (eventMapper.findByEventId(event.getEventId()) != null) {
            return;
        }
        InstallationOperationEventEntity latest = eventMapper.findLatest(operation.getId());
        if (latest != null && latest.getEventSequence() >= event.getSequence()) {
            throw new IllegalArgumentException("Installation event sequence is stale");
        }
        InstallationOperationEventEntity stored = eventEntity(event, operation.getId());
        if (eventMapper.insert(stored) == 0) {
            return;
        }
        apply(operation, event, actor);
    }

    private void apply(InstallationOperationEntity operation, InstallationEvent event, String actor) {
        InstallationInstanceEntity instance = instanceMapper.findById(event.getInstanceId());
        if (instance == null) {
            throw new IllegalArgumentException("Installation instance not found");
        }
        String eventType = event.getEventType();
        InstallationState target = targetState(operation, eventType, event.getStage());
        InstallationState current = parseState(operation.getOperationState());
        InstallationStateMachine.transition(current, target);
        operation.setOperationStage(event.getStage());
        operation.setOperationState(target.name());
        operation.setFailureStage("FAILED".equals(eventType) ? event.getStage() : operation.getFailureStage());
        operation.setErrorCode(event.getErrorCode());
        operation.setErrorReason(event.getErrorReason());
        operation.setStartedAt(operation.getStartedAt() == null ? OffsetDateTime.now() : operation.getStartedAt());
        operation.setCompletedAt(target.isTerminal() ? OffsetDateTime.now() : operation.getCompletedAt());
        if (target == InstallationState.ROLLED_BACK) operation.setRollbackState("SUCCEEDED");

        instance.setUpdatedBy(actor == null ? "runtime-adapter" : actor);
        instance.setRowVersion(instance.getRowVersion() == null ? 0L : instance.getRowVersion());
        applyInstanceState(instance, operation, event, target);
        if (target == InstallationState.SUCCEEDED
                && !OperationType.REVOKE.name().equals(operationType(operation))
                && !eligibilityPolicy.canEnable(instance.getSkillState(), instance.getTrackerState(),
                instance.getHealthStatus())) {
            throw new IllegalArgumentException("Installation cannot be enabled before health confirmation");
        }
        operationMapper.updateProgress(operation);
        instanceMapper.updateProgress(instance);
        updateTracker(instance, event, actor);
    }

    private void applyInstanceState(InstallationInstanceEntity instance, InstallationOperationEntity operation,
                                    InstallationEvent event, InstallationState target) {
        if ("INSTALLING_SKILL".equals(event.getStage()) || "SUCCEEDED".equals(event.getEventType())) {
            instance.setSkillState("SUCCEEDED".equals(event.getEventType()) ? "READY" : "INSTALLING");
        }
        if ("TRACKER_READY".equals(event.getEventType())) instance.setTrackerState("READY");
        if ("FAILED".equals(event.getEventType()) && "INSTALLING_TRACKER".equals(event.getStage())) {
            instance.setTrackerState("FAILED");
        }
        if ("HEALTH_CHECKED".equals(event.getEventType())) {
            instance.setHealthStatus(event.getErrorCode() == null ? "HEALTHY" : "UNHEALTHY");
            instance.setLastHealthAt(OffsetDateTime.now());
        }
        if ("FAILED".equals(event.getEventType())) {
            instance.setLastErrorCode(event.getErrorCode());
            instance.setLastErrorReason(event.getErrorReason());
        }
        if (target == InstallationState.SUCCEEDED && !OperationType.REVOKE.name().equals(operationType(operation))) {
            instance.setCurrentVersionDigest(event.getVersionDigest());
            instance.setInstalledAt(OffsetDateTime.now());
        }
        if (target == InstallationState.REVOKED) {
            instance.setDesiredState(InstallationState.REVOKED.name());
            instance.setOverallState(InstallationState.REVOKED.name());
        }
        if (target == InstallationState.REQUIRES_MANUAL && OperationType.REVOKE.name().equals(operationType(operation))) {
            instance.setDesiredState(InstallationState.REVOKE_REQUESTED.name());
            instance.setOverallState(InstallationState.REQUIRES_MANUAL.name());
        }
        instance.setOverallState(eligibilityPolicy.overallState(instance.getSkillState(), instance.getTrackerState(),
                instance.getHealthStatus()));
        if (target == InstallationState.REVOKED
                || (target == InstallationState.REQUIRES_MANUAL
                && OperationType.REVOKE.name().equals(operationType(operation)))) {
            instance.setOverallState(target.name());
        }
        if (target == InstallationState.FAILED && instance.getCurrentVersionDigest() == null) {
            instance.setOverallState(InstallationState.UNAVAILABLE.name());
        }
    }

    private void updateTracker(InstallationInstanceEntity instance, InstallationEvent event, String actor) {
        if (!"TRACKER_READY".equals(event.getEventType()) && !"FAILED".equals(event.getEventType())) return;
        TrackerBindingEntity binding = trackerBindingMapper.findByInstanceAndKey(instance.getId(), TrackerBindingService.DEFAULT_TRACKER_KEY);
        if (binding == null) return;
        binding.setRowVersion(binding.getRowVersion() == null ? 0L : binding.getRowVersion());
        binding.setUpdatedBy(actor == null ? "runtime-adapter" : actor);
        binding.setInstallationState("TRACKER_READY".equals(event.getEventType()) ? "READY" : "FAILED");
        binding.setHealthState("TRACKER_READY".equals(event.getEventType()) ? "UNKNOWN" : "UNHEALTHY");
        binding.setLastErrorCode(event.getErrorCode());
        binding.setLastErrorReason(event.getErrorReason());
        trackerBindingMapper.updateProgress(binding);
    }

    private InstallationOperationEventEntity eventEntity(InstallationEvent event, Long operationId) {
        InstallationOperationEventEntity entity = new InstallationOperationEventEntity();
        entity.setEventId(event.getEventId()); entity.setOperationId(operationId);
        entity.setEventSequence(event.getSequence()); entity.setEventType(event.getEventType());
        entity.setEventState(event.getStage()); entity.setOccurredAt(OffsetDateTime.now());
        try { entity.setPayload(objectMapper.writeValueAsString(event)); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Installation event payload is invalid"); }
        return entity;
    }

    private InstallationState targetState(InstallationOperationEntity operation, String eventType, String stage) {
        if (OperationType.REVOKE.name().equals(operationType(operation)) && "ACCEPTED".equals(eventType)) {
            return InstallationState.REVOKING;
        }
        if ("ACCEPTED".equals(eventType)) return InstallationState.VALIDATING;
        if ("STAGE_CHANGED".equals(eventType)) return InstallationState.valueOf(stage);
        if ("TRACKER_READY".equals(eventType)) return InstallationState.CONFIGURING;
        if ("HEALTH_CHECKED".equals(eventType)) return InstallationState.VERIFYING;
        if (OperationType.REVOKE.name().equals(operationType(operation)) && "SUCCEEDED".equals(eventType)) {
            return InstallationState.REVOKED;
        }
        if ("REVOKED".equals(eventType)) return InstallationState.REVOKED;
        if (OperationType.REVOKE.name().equals(operationType(operation)) && "FAILED".equals(eventType)) {
            return InstallationState.REQUIRES_MANUAL;
        }
        if ("SUCCEEDED".equals(eventType)) return InstallationState.SUCCEEDED;
        if ("FAILED".equals(eventType)) return InstallationState.FAILED;
        if ("ROLLED_BACK".equals(eventType)) return InstallationState.ROLLED_BACK;
        if ("REQUIRES_MANUAL".equals(eventType)) return InstallationState.REQUIRES_MANUAL;
        throw new IllegalArgumentException("Installation event type is unsupported");
    }

    private String operationType(InstallationOperationEntity operation) {
        return operation == null ? null : operation.getOperationType();
    }

    private boolean versionMatches(InstallationOperationEntity operation, InstallationEvent event) {
        if ("ROLLED_BACK".equals(event.getEventType())) {
            return event.getVersionDigest().equals(operation.getPreviousVersionDigest());
        }
        return event.getVersionDigest().equals(operation.getTargetVersionDigest());
    }

    private InstallationState parseState(String value) {
        try { return InstallationState.valueOf(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Installation operation state is invalid"); }
    }
}
