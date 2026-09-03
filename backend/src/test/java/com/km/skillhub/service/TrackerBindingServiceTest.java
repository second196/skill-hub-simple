package com.km.skillhub.service;

import com.km.skillhub.installation.mapper.TrackerBindingMapper;
import com.km.skillhub.installation.model.entity.TrackerBindingEntity;
import com.km.skillhub.installation.service.TrackerBindingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackerBindingServiceTest {
    @Test
    void createsBindingInNotInstalledState() {
        TrackerBindingMapper mapper = mock(TrackerBindingMapper.class);
        when(mapper.findByInstanceAndKey(10L, "skill-tracker")).thenReturn(null);
        when(mapper.insert(any(TrackerBindingEntity.class))).thenAnswer(invocation -> {
            TrackerBindingEntity value = invocation.getArgument(0);
            value.setId(20L);
            return 1;
        });

        TrackerBindingEntity binding = new TrackerBindingService(mapper).prepare(10L, "skill-tracker", "1.0.0",
                repeat('a', 64), "admin");

        assertEquals("NOT_INSTALLED", binding.getInstallationState());
        assertEquals("UNKNOWN", binding.getHealthState());
        assertEquals(20L, binding.getId());
        verify(mapper).insert(any(TrackerBindingEntity.class));
    }

    @Test
    void reusesExistingBindingForIdempotentRequest() {
        TrackerBindingMapper mapper = mock(TrackerBindingMapper.class);
        TrackerBindingEntity existing = new TrackerBindingEntity();
        existing.setId(20L);
        when(mapper.findByInstanceAndKey(10L, "skill-tracker")).thenReturn(existing);

        assertSame(existing, new TrackerBindingService(mapper).prepare(10L, "skill-tracker", "1.0.0", null, "admin"));
    }

    @Test
    void refreshesTrackerDefinitionWhenTheTargetVersionChanges() {
        TrackerBindingMapper mapper = mock(TrackerBindingMapper.class);
        TrackerBindingEntity existing = new TrackerBindingEntity();
        existing.setId(20L);
        existing.setTrackerVersion("1.0.0");
        existing.setConfigurationDigest(repeat('a', 64));
        when(mapper.findByInstanceAndKey(10L, "skill-tracker")).thenReturn(existing);

        TrackerBindingEntity result = new TrackerBindingService(mapper).prepare(10L, "skill-tracker", "2.0.0",
                repeat('b', 64), "admin");

        assertSame(existing, result);
        assertEquals("2.0.0", existing.getTrackerVersion());
        assertEquals(repeat('b', 64), existing.getConfigurationDigest());
        verify(mapper).updateDefinition(existing);
    }

    @Test
    void rejectsIncompleteBinding() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackerBindingService(mock(TrackerBindingMapper.class)).prepare(10L, "", "1.0.0", null, "admin"));
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
