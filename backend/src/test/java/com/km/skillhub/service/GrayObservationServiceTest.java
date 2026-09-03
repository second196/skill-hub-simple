package com.km.skillhub.service;

import com.km.skillhub.gate.model.GrayObservation;
import com.km.skillhub.gate.service.GrayObservationService;
import com.km.skillhub.policy.model.entity.ReleasePolicyVersionEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GrayObservationServiceTest {
    @Test
    void waitsForTheConfiguredWindowAndCalls() {
        GrayObservation observation = observation(); observation.setObservedSeconds(3600L);
        assertEquals("PENDING_GRAY", new GrayObservationService().evaluate(observation, policy()).getState());
    }
    @Test
    void requestsRollbackWhenErrorThresholdIsBreached() {
        GrayObservation observation = observation(); observation.setObservedSeconds(86400L); observation.setErrorRate(0.03D);
        assertEquals("ROLLBACK_REQUIRED", new GrayObservationService().evaluate(observation, policy()).getState());
    }
    private GrayObservation observation() {
        GrayObservation value = new GrayObservation(); value.setVersionDigest(repeat("a", 64)); value.setInstanceCount(1);
        value.setValidCalls(30); value.setObservedSeconds(86400L); value.setBaselineErrorRate(0D);
        value.setErrorRate(0.01D); value.setBaselineScore(0.90D); value.setScore(0.90D); return value;
    }
    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    private ReleasePolicyVersionEntity policy() {
        ReleasePolicyVersionEntity value = new ReleasePolicyVersionEntity(); value.setMinimumValidCalls(30); value.setObservationWindowSeconds(86400L); return value;
    }
}
