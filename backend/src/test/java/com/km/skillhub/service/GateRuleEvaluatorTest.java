package com.km.skillhub.service;

import com.km.skillhub.gate.domain.GateEvidenceSnapshot;
import com.km.skillhub.gate.domain.GateRuleEvaluator;
import com.km.skillhub.policy.model.entity.ReleasePolicyVersionEntity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GateRuleEvaluatorTest {
    @Test
    void passesCompleteLowRiskAutoReleaseEvidence() {
        GateRuleEvaluator.Result result = new GateRuleEvaluator().evaluate(evidence(false, "PASS"), policy(), "AUTO");
        assertEquals("APPROVED", result.getState());
    }

    @Test
    void blocksHighRiskEvidence() {
        GateRuleEvaluator.Result result = new GateRuleEvaluator().evaluate(evidence(true, "PASS"), policy(), "AUTO");
        assertEquals("BLOCKED", result.getState());
    }

    @Test
    void keepsGrayReleasePendingUntilObservationCompletes() {
        GateRuleEvaluator.Result result = new GateRuleEvaluator().evaluate(evidence(false, "PASS"), policy(), "GRAY");
        assertEquals("PENDING_GRAY", result.getState());
    }

    private ReleasePolicyVersionEntity policy() {
        ReleasePolicyVersionEntity policy = new ReleasePolicyVersionEntity();
        policy.setMinimumValidCases(30); policy.setMinimumValidCalls(30);
        return policy;
    }

    private java.util.List<GateEvidenceSnapshot> evidence(boolean highRisk, String result) {
        GateEvidenceSnapshot evaluation = new GateEvidenceSnapshot("EVALUATION", result, 30, 30,
                0.01D, 0.01D, 0.90D, 0.90D, false, false, true, true, false);
        GateEvidenceSnapshot scan = new GateEvidenceSnapshot("STATIC_SCAN", result, 0, 0,
                0D, 0D, 0D, 0D, false, false, true, true, false);
        GateEvidenceSnapshot risk = new GateEvidenceSnapshot("RISK", result, 0, 0,
                0D, 0D, 0D, 0D, highRisk, false, true, true, false);
        GateEvidenceSnapshot review = new GateEvidenceSnapshot("REVIEW", result, 0, 0,
                0D, 0D, 0D, 0D, false, false, true, true, false);
        return Arrays.asList(evaluation, scan, risk, review);
    }
}
