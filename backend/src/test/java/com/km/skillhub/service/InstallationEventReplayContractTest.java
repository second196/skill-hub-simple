package com.km.skillhub.service;

import com.km.skillhub.installation.mapper.InstallationOperationEventMapper;
import com.km.skillhub.integration.runtime.InstallationEventDeduplicationService;
import com.km.skillhub.installation.model.entity.InstallationOperationEventEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstallationEventReplayContractTest {
    @Test
    void identifiesPreviouslyStoredEvent() {
        InstallationOperationEventMapper mapper = mock(InstallationOperationEventMapper.class);
        when(mapper.findByEventId("event-1")).thenReturn(new InstallationOperationEventEntity());
        InstallationEventDeduplicationService service = new InstallationEventDeduplicationService(mapper);
        assertTrue(service.alreadyProcessed("event-1"));
        when(mapper.findByEventId("event-2")).thenReturn(null);
        assertFalse(service.alreadyProcessed("event-2"));
    }
}
