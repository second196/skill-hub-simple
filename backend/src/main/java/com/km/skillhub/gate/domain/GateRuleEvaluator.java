package com.km.skillhub.gate.domain;

import com.km.skillhub.policy.model.entity.ReleasePolicyVersionEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GateRuleEvaluator {
    public Result evaluate(List<GateEvidenceSnapshot> evidence, ReleasePolicyVersionEntity policy,
                           String releaseMode) {
        List<String> reasons = new ArrayList<String>();
        Set<String> types = new HashSet<String>();
        boolean highRisk = false;
        for (GateEvidenceSnapshot item : evidence) {
            types.add(item.getEvidenceType());
            highRisk = highRisk || item.isHighRisk();
            if (item.isExpired()) reasons.add("evidence_expired:" + item.getEvidenceType());
            if (!"PASS".equals(item.getResult())) reasons.add("evidence_not_pass:" + item.getEvidenceType());
            if (!item.isRuntimeCompatible()) reasons.add("runtime_incompatible");
            if (!item.isRegressionPassed()) reasons.add("regression_failed");
            if (item.isUnauthorizedChange()) reasons.add("unauthorized_change");
            if ("EVALUATION".equals(item.getEvidenceType()) || "GRAY_OBSERVATION".equals(item.getEvidenceType())) {
                if (item.getBaselineErrorRate() == 0 && item.getErrorRate() >= 0.02D) reasons.add("error_rate_absolute");
                if (item.getBaselineErrorRate() > 0 && item.getErrorRate() - item.getBaselineErrorRate() >= 0.02D) reasons.add("error_rate_delta");
                if (item.getBaselineErrorRate() > 0 && item.getErrorRate() / item.getBaselineErrorRate() >= 1.20D) reasons.add("error_rate_relative");
                if (item.getBaselineScore() - item.getScore() >= 0.05D) reasons.add("score_drop");
            }
            if ("EVALUATION".equals(item.getEvidenceType()) && item.getValidCases() < policy.getMinimumValidCases()) {
                reasons.add("valid_cases_below_minimum");
            }
            if (("EVALUATION".equals(item.getEvidenceType()) || "GRAY_OBSERVATION".equals(item.getEvidenceType()))
                    && item.getValidCalls() < policy.getMinimumValidCalls()) reasons.add("valid_calls_below_minimum");
        }
        String state = "APPROVED";
        if (!types.contains("STATIC_SCAN") || !types.contains("EVALUATION") || !types.contains("RISK")
                || !types.contains("REVIEW")) reasons.add("required_evidence_missing");
        if (highRisk) reasons.add("high_risk");
        if (!reasons.isEmpty()) state = highRisk ? "BLOCKED" : "PENDING_APPROVAL";
        else if ("GRAY".equals(releaseMode)) state = "PENDING_GRAY";
        else if (!"AUTO".equals(releaseMode)) state = "PENDING_APPROVAL";
        return new Result(state, reasons, highRisk);
    }

    public static class Result {
        private final String state;
        private final List<String> reasons;
        private final boolean highRisk;
        public Result(String state, List<String> reasons, boolean highRisk) {
            this.state = state; this.reasons = reasons; this.highRisk = highRisk;
        }
        public String getState() { return state; }
        public List<String> getReasons() { return reasons; }
        public boolean isHighRisk() { return highRisk; }
    }
}
