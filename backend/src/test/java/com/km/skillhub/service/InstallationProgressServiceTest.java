package com.km.skillhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.installation.mapper.InstallationInstanceMapper;
import com.km.skillhub.installation.mapper.InstallationOperationEventMapper;
import com.km.skillhub.installation.mapper.InstallationOperationMapper;
import com.km.skillhub.installation.mapper.TrackerBindingMapper;
import com.km.skillhub.installation.model.entity.InstallationInstanceEntity;
import com.km.skillhub.installation.model.entity.InstallationOperationEntity;
import com.km.skillhub.installation.service.InstallationEligibilityPolicy;
import com.km.skillhub.installation.service.InstallationProgressService;
import com.km.skillhub.integration.runtime.InstallationEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstallationProgressServiceTest {
    @Test
    void acceptsProgressWithoutEnablingTheInstallation() {
        InstallationOperationMapper operationMapper = mock(InstallationOperationMapper.class);
        InstallationOperationEventMapper eventMapper = mock(InstallationOperationEventMapper.class);
        InstallationInstanceMapper instanceMapper = mock(InstallationInstanceMapper.class);
        TrackerBindingMapper trackerMapper = mock(TrackerBindingMapper.class);
        InstallationOperationEntity operation = operation("INSTALL", "REQUESTED", null);
        InstallationInstanceEntity instance = instance();
        when(operationMapper.findByOperationId("op-1")).thenReturn(operation);
        when(eventMapper.findByEventId(any(String.class))).thenReturn(null);
        when(eventMapper.findLatest(1L)).thenReturn(null);
        when(eventMapper.insert(any())).thenReturn(1);
        when(instanceMapper.findById(10L)).thenReturn(instance);

        InstallationProgressService service = new InstallationProgressService(operationMapper, eventMapper,
                instanceMapper, trackerMapper, new InstallationEligibilityPolicy(), new ObjectMapper());

        service.accept(event("e-1", 1, "ACCEPTED", "VALIDATING", digest()), "runtime-adapter");
        assertEquals("VALIDATING", operation.getOperationState());
        assertEquals(digest(), instance.getCurrentVersionDigest());
        verify(operationMapper).updateProgress(operation);
    }

    @Test
    void rejectsSuccessBeforeHealthConfirmation() {
        InstallationOperationMapper operationMapper = mock(InstallationOperationMapper.class);
        InstallationOperationEventMapper eventMapper = mock(InstallationOperationEventMapper.class);
        InstallationInstanceMapper instanceMapper = mock(InstallationInstanceMapper.class);
        InstallationOperationEntity operation = operation("INSTALL", "VERIFYING", null);
        InstallationInstanceEntity instance = instance();
        instance.setSkillState("READY");
        instance.setTrackerState("READY");
        instance.setHealthStatus("UNKNOWN");
        when(operationMapper.findByOperationId("op-1")).thenReturn(operation);
        when(eventMapper.findByEventId(any(String.class))).thenReturn(null);
        when(eventMapper.findLatest(1L)).thenReturn(null);
        when(eventMapper.insert(any())).thenReturn(1);
        when(instanceMapper.findById(10L)).thenReturn(instance);

        InstallationProgressService service = new InstallationProgressService(operationMapper, eventMapper,
                instanceMapper, mock(TrackerBindingMapper.class), new InstallationEligibilityPolicy(), new ObjectMapper());

        assertThrows(IllegalArgumentException.class,
                () -> service.accept(event("e-health-missing", 1, "SUCCEEDED", "SUCCEEDED", digest()),
                        "runtime-adapter"));
    }

    @Test
    void rejectsAnOlderSequenceBeforeApplyingIt() {
        InstallationOperationMapper operationMapper = mock(InstallationOperationMapper.class);
        InstallationOperationEventMapper eventMapper = mock(InstallationOperationEventMapper.class);
        InstallationOperationEntity operation = operation("INSTALL", "DOWNLOADING", null);
        when(operationMapper.findByOperationId("op-1")).thenReturn(operation);
        com.km.skillhub.installation.model.entity.InstallationOperationEventEntity latest =
                new com.km.skillhub.installation.model.entity.InstallationOperationEventEntity();
        latest.setEventSequence(3);
        when(eventMapper.findByEventId(any(String.class))).thenReturn(null);
        when(eventMapper.findLatest(1L)).thenReturn(latest);

        InstallationProgressService service = new InstallationProgressService(operationMapper, eventMapper,
                mock(InstallationInstanceMapper.class), mock(TrackerBindingMapper.class),
                new InstallationEligibilityPolicy(), new ObjectMapper());

        assertThrows(IllegalArgumentException.class,
                () -> service.accept(event("e-2", 2, "STAGE_CHANGED", "INSTALLING_SKILL", digest()), "runtime-adapter"));
    }

    @Test
    void marksRevocationSuccessfulWithoutChangingTheRecordedVersion() {
        InstallationOperationMapper operationMapper = mock(InstallationOperationMapper.class);
        InstallationOperationEventMapper eventMapper = mock(InstallationOperationEventMapper.class);
        InstallationInstanceMapper instanceMapper = mock(InstallationInstanceMapper.class);
        InstallationInstanceEntity instance = instance();
        InstallationOperationEntity operation = operation("REVOKE", "REVOKE_REQUESTED", digest());
        when(operationMapper.findByOperationId("op-1")).thenReturn(operation);
        when(eventMapper.findByEventId(any(String.class))).thenReturn(null);
        when(eventMapper.findLatest(1L)).thenReturn(null);
        when(eventMapper.insert(any())).thenReturn(1);
        when(instanceMapper.findById(10L)).thenReturn(instance);

        InstallationProgressService service = new InstallationProgressService(operationMapper, eventMapper,
                instanceMapper, mock(TrackerBindingMapper.class), new InstallationEligibilityPolicy(), new ObjectMapper());
        service.accept(event("e-3", 1, "ACCEPTED", "REVOKING", digest()), "runtime-adapter");
        service.accept(event("e-4", 2, "SUCCEEDED", "REVOKED", digest()), "runtime-adapter");

        assertEquals("REVOKED", operation.getOperationState());
        assertEquals("REVOKED", instance.getOverallState());
        assertEquals(digest(), instance.getCurrentVersionDigest());
    }

    private InstallationOperationEntity operation(String type, String state, String previous) {
        InstallationOperationEntity operation = new InstallationOperationEntity();
        operation.setId(1L); operation.setOperationId("op-1"); operation.setOperationType(type);
        operation.setOperationState(state); operation.setOperationStage(state);
        operation.setInstallationInstanceId(10L); operation.setAssetId(7L); operation.setTargetVersionDigest(digest());
        operation.setPreviousVersionDigest(previous); operation.setRuntimeKey("codex-cli");
        operation.setRuntimeVersion("initial"); operation.setScopeId(1L);
        return operation;
    }

    private InstallationInstanceEntity instance() {
        InstallationInstanceEntity instance = new InstallationInstanceEntity();
        instance.setId(10L); instance.setAssetId(7L); instance.setCurrentVersionDigest(digest());
        instance.setRuntimeKey("codex-cli"); instance.setRuntimeVersion("initial"); instance.setScopeId(1L);
        instance.setSkillState("READY"); instance.setTrackerState("READY"); instance.setHealthStatus("HEALTHY");
        instance.setRowVersion(0L);
        return instance;
    }

    private InstallationEvent event(String id, int sequence, String type, String stage, String version) {
        return new InstallationEvent(id, "op-1", sequence, type, 10L, 7L, version, digest(),
                "codex-cli", "initial", 1L, stage, null, null);
    }

    private String digest() {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) result.append('a');
        return result.toString();
    }
}
