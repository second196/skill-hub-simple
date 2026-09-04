package com.km.skillhub.service;

import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.installation.mapper.RuntimeIntegrationEventMapper;
import com.km.skillhub.installation.mapper.RuntimeIntegrationInstanceMapper;
import com.km.skillhub.installation.model.dto.RuntimeIntegrationEventRequest;
import com.km.skillhub.installation.model.dto.RuntimeIntegrationRegistration;
import com.km.skillhub.installation.model.entity.RuntimeIntegrationEventEntity;
import com.km.skillhub.installation.model.entity.RuntimeIntegrationInstanceEntity;
import com.km.skillhub.installation.model.vo.RuntimeIntegrationVO;
import com.km.skillhub.installation.service.RuntimeIntegrationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeIntegrationServiceTest {

    @Test
    void returnsExistingRegistrationWhenConcurrentInsertWins() {
        Fixture fixture = fixture();
        RuntimeIntegrationInstanceEntity existing = instance();
        when(fixture.instances.supportsTelemetry("codex-cli", "initial")).thenReturn(true);
        when(fixture.instances.findByTarget(1L, "codex-cli", digest('a')))
                .thenReturn(null, existing);
        when(fixture.instances.insert(any(RuntimeIntegrationInstanceEntity.class))).thenReturn(0);
        when(fixture.instances.findByIntegrationId("integration-1")).thenReturn(existing);

        RuntimeIntegrationVO result = fixture.service.register(registration(), "admin");

        assertEquals("integration-1", result.getIntegrationId());
    }

    @Test
    void rejectsRuntimeWithoutTelemetryCapability() {
        Fixture fixture = fixture();
        when(fixture.instances.supportsTelemetry("codex-cli", "initial")).thenReturn(false);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> fixture.service.register(registration(), "admin"));

        assertEquals("运行时未启用或不支持运行数据接入", exception.getMessage());
    }

    @Test
    void returnsCurrentStateWhenConcurrentDuplicateEventWins() {
        Fixture fixture = fixture();
        RuntimeIntegrationInstanceEntity instance = instance();
        RuntimeIntegrationEventEntity duplicate = new RuntimeIntegrationEventEntity();
        duplicate.setRuntimeIntegrationInstanceId(instance.getId());
        when(fixture.instances.findByIntegrationId("integration-1")).thenReturn(instance);
        when(fixture.events.findByEventId("event-1")).thenReturn(null, duplicate);
        when(fixture.events.insert(any(RuntimeIntegrationEventEntity.class))).thenAnswer(invocation -> {
            instance.setLastEventSequence(1);
            return 0;
        });

        RuntimeIntegrationVO result = fixture.service.acceptEvent(
                "integration-1", event(1, "C:\\Users\\admin\\.skillhub\\state.json"), "admin");

        assertEquals(Integer.valueOf(1), result.getLastEventSequence());
    }

    @Test
    void rejectsEventOlderThanCurrentState() {
        Fixture fixture = fixture();
        RuntimeIntegrationInstanceEntity instance = instance();
        instance.setLastEventSequence(3);
        when(fixture.instances.findByIntegrationId("integration-1")).thenReturn(instance);
        when(fixture.events.findByEventId("event-1")).thenReturn(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> fixture.service.acceptEvent("integration-1", event(2, null), "admin"));

        assertEquals("接入事件序列早于当前状态", exception.getMessage());
    }

    @Test
    void redactsTokensAndAbsolutePathsBeforePersistence() {
        Fixture fixture = fixture();
        RuntimeIntegrationInstanceEntity instance = instance();
        when(fixture.instances.findByIntegrationId("integration-1")).thenReturn(instance);
        when(fixture.events.findByEventId("event-1")).thenReturn(null);
        when(fixture.events.insert(any(RuntimeIntegrationEventEntity.class))).thenReturn(1);
        when(fixture.instances.updateFromEvent(any(RuntimeIntegrationInstanceEntity.class))).thenReturn(1);
        ArgumentCaptor<RuntimeIntegrationEventEntity> eventCaptor =
                ArgumentCaptor.forClass(RuntimeIntegrationEventEntity.class);

        fixture.service.acceptEvent("integration-1",
                event(1, "读取 C:\\Users\\admin\\.skillhub\\state.json 失败，Bearer sk_secret-token"),
                "admin");

        verify(fixture.events).insert(eventCaptor.capture());
        String persistedReason = eventCaptor.getValue().getErrorReason();
        assertFalse(persistedReason.contains("C:\\Users"));
        assertFalse(persistedReason.contains("sk_secret-token"));
        assertEquals("读取 [本地路径已脱敏] 失败，[访问凭证已脱敏]", persistedReason);
    }

    @Test
    void reportsConcurrentStateUpdateInsteadOfOverwritingIt() {
        Fixture fixture = fixture();
        RuntimeIntegrationInstanceEntity instance = instance();
        when(fixture.instances.findByIntegrationId("integration-1")).thenReturn(instance);
        when(fixture.events.findByEventId("event-1")).thenReturn(null);
        when(fixture.events.insert(any(RuntimeIntegrationEventEntity.class))).thenReturn(1);
        when(fixture.instances.updateFromEvent(any(RuntimeIntegrationInstanceEntity.class))).thenReturn(0);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> fixture.service.acceptEvent("integration-1", event(1, null), "admin"));

        assertEquals("运行时接入状态已被并发修改", exception.getMessage());
    }

    private Fixture fixture() {
        RuntimeIntegrationInstanceMapper instances = mock(RuntimeIntegrationInstanceMapper.class);
        RuntimeIntegrationEventMapper events = mock(RuntimeIntegrationEventMapper.class);
        AuthorizationService authorization = mock(AuthorizationService.class);
        return new Fixture(instances, events,
                new RuntimeIntegrationService(instances, events, authorization));
    }

    private RuntimeIntegrationRegistration registration() {
        RuntimeIntegrationRegistration request = new RuntimeIntegrationRegistration();
        request.setScopeId(1L);
        request.setRuntimeKey("codex-cli");
        request.setRuntimeVersion("initial");
        request.setTargetKey(digest('a'));
        request.setAdapterVersion("0.1.0");
        request.setConfigurationDigest(digest('b'));
        request.setInstallationState("ACTIVE");
        request.setHealthStatus("HEALTHY");
        return request;
    }

    private RuntimeIntegrationEventRequest event(int sequence, String reason) {
        RuntimeIntegrationEventRequest request = new RuntimeIntegrationEventRequest();
        request.setEventId("event-1");
        request.setEventSequence(sequence);
        request.setEventType("CHECK_COMPLETED");
        request.setStage("VERIFYING");
        request.setResult("SUCCEEDED");
        request.setInstallationState("ACTIVE");
        request.setHealthStatus("HEALTHY");
        request.setErrorReason(reason);
        request.setOccurredAt(OffsetDateTime.parse("2026-09-03T06:00:00Z"));
        return request;
    }

    private RuntimeIntegrationInstanceEntity instance() {
        RuntimeIntegrationInstanceEntity instance = new RuntimeIntegrationInstanceEntity();
        instance.setId(7L);
        instance.setIntegrationId("integration-1");
        instance.setScopeId(1L);
        instance.setRuntimeKey("codex-cli");
        instance.setRuntimeVersion("initial");
        instance.setTargetKey(digest('a'));
        instance.setAdapterVersion("0.1.0");
        instance.setConfigurationDigest(digest('b'));
        instance.setInstallationState("ACTIVE");
        instance.setHealthStatus("HEALTHY");
        instance.setLastEventSequence(0);
        instance.setRowVersion(0L);
        return instance;
    }

    private String digest(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int index = 0; index < 64; index++) result.append(value);
        return result.toString();
    }

    private static class Fixture {
        private final RuntimeIntegrationInstanceMapper instances;
        private final RuntimeIntegrationEventMapper events;
        private final RuntimeIntegrationService service;

        private Fixture(RuntimeIntegrationInstanceMapper instances, RuntimeIntegrationEventMapper events,
                        RuntimeIntegrationService service) {
            this.instances = instances;
            this.events = events;
            this.service = service;
        }
    }
}
