package com.km.skillhub.gate.service;

import com.km.skillhub.gate.model.GrayObservation;
import com.km.skillhub.policy.model.entity.ReleasePolicyVersionEntity;
import org.springframework.stereotype.Service;

@Service
public class GrayObservationService {
    public ObservationResult evaluate(GrayObservation observation, ReleasePolicyVersionEntity policy) {
        if (observation == null || observation.getVersionDigest() == null || observation.getVersionDigest().trim().isEmpty()) {
            throw new IllegalArgumentException("Gray observation version digest is required");
        }
        int minimumCalls = policy == null || policy.getMinimumValidCalls() == null ? 30 : policy.getMinimumValidCalls();
        long window = policy == null || policy.getObservationWindowSeconds() == null ? 86400L : policy.getObservationWindowSeconds();
        boolean enough = observation.getInstanceCount() >= 1 && observation.getValidCalls() >= minimumCalls
                && observation.getObservedSeconds() >= window;
        boolean rollback = observation.isHighRisk() || observation.isSeriousIssue()
                || (observation.getBaselineErrorRate() == 0 && observation.getErrorRate() >= 0.02D)
                || (observation.getBaselineErrorRate() > 0 && observation.getErrorRate() - observation.getBaselineErrorRate() >= 0.02D)
                || (observation.getBaselineErrorRate() > 0 && observation.getErrorRate() / observation.getBaselineErrorRate() >= 1.20D)
                || observation.getBaselineScore() - observation.getScore() >= 0.05D;
        if (rollback) return new ObservationResult("ROLLBACK_REQUIRED", "gray_threshold_breached");
        return new ObservationResult(enough ? "OBSERVATION_PASSED" : "PENDING_GRAY",
                enough ? "minimum_window_and_calls_reached" : "minimum_window_or_calls_not_reached");
    }

    public static class ObservationResult {
        private final String state; private final String reason;
        public ObservationResult(String state, String reason) { this.state = state; this.reason = reason; }
        public String getState() { return state; }
        public String getReason() { return reason; }
    }
}
