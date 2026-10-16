package com.km.skillhub.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Five-gate evaluation for optimize candidates (pure functions):
 * improvement / regression / tests / budget / compare.
 *
 * <p>Defaults (aligned with kmplm design §3.10.3): improvement ≥ 0.02,
 * regression ≤ 0.02, tests all pass, edit budget ≤ 4, paired compare not majority-worse.</p>
 */
public class SkillOptimizeGate {

    public static final double DEFAULT_MIN_IMPROVEMENT = 0.02d;
    public static final double DEFAULT_MAX_REGRESSION = 0.02d;
    public static final int DEFAULT_EDIT_BUDGET = 4;

    public static final String GATE_IMPROVEMENT = "improvement";
    public static final String GATE_REGRESSION = "regression";
    public static final String GATE_TESTS = "tests";
    public static final String GATE_BUDGET = "budget";
    public static final String GATE_COMPARE = "compare";

    public static final String VOTE_BETTER = "better";
    public static final String VOTE_WORSE = "worse";
    public static final String VOTE_TIE = "tie";

    public static final String EVIDENCE_MEASURED = "measured";
    public static final String EVIDENCE_STATIC_MEASURED = "static_measured";
    public static final String EVIDENCE_SELF_REPORTED = "self_reported";

    private final double minImprovement;
    private final double maxRegression;
    private final int editBudget;

    public SkillOptimizeGate() {
        this(DEFAULT_MIN_IMPROVEMENT, DEFAULT_MAX_REGRESSION, DEFAULT_EDIT_BUDGET);
    }

    public SkillOptimizeGate(double minImprovement, double maxRegression, int editBudget) {
        this.minImprovement = minImprovement;
        this.maxRegression = maxRegression;
        this.editBudget = editBudget <= 0 ? DEFAULT_EDIT_BUDGET : editBudget;
    }

    public double getMinImprovement() { return minImprovement; }
    public double getMaxRegression() { return maxRegression; }
    public int getEditBudget() { return editBudget; }

    /** Metrics snapshot; numeric values are 0..1 (qualityScore is 0..100, normalized on compare). */
    public static class Metrics {
        private Double coverage;
        private Double failureRate;
        private Double qualityScore;

        public Metrics() {
        }

        public Metrics(Double coverage, Double failureRate, Double qualityScore) {
            this.coverage = coverage;
            this.failureRate = failureRate;
            this.qualityScore = qualityScore;
        }

        public Double getCoverage() { return coverage; }
        public void setCoverage(Double coverage) { this.coverage = coverage; }
        public Double getFailureRate() { return failureRate; }
        public void setFailureRate(Double failureRate) { this.failureRate = failureRate; }
        public Double getQualityScore() { return qualityScore; }
        public void setQualityScore(Double qualityScore) { this.qualityScore = qualityScore; }
    }

    /** One bounded edit. */
    public static class Edit {
        private String target;
        private String op;
        private boolean protectedZone;

        public Edit() {
        }

        public Edit(String target, String op, boolean protectedZone) {
            this.target = target;
            this.op = op;
            this.protectedZone = protectedZone;
        }

        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getOp() { return op; }
        public void setOp(String op) { this.op = op; }
        public boolean isProtectedZone() { return protectedZone; }
        public void setProtectedZone(boolean protectedZone) { this.protectedZone = protectedZone; }
    }

    /** Tests and paired comparison evidence. */
    public static class Evaluation {
        private boolean requireTests = true;
        private boolean hasTests;
        private boolean allPassed;
        private int testTotal;
        private int testPassed;
        private List<String> pairedVotes = new ArrayList<String>();

        public Evaluation() {
        }

        public static Evaluation testsPassed(int total, int passed, List<String> pairedVotes) {
            Evaluation evidence = new Evaluation();
            evidence.setRequireTests(true);
            evidence.setHasTests(total > 0);
            evidence.setTestTotal(total);
            evidence.setTestPassed(passed);
            evidence.setAllPassed(total > 0 && passed >= total);
            evidence.setPairedVotes(pairedVotes);
            return evidence;
        }

        public boolean isRequireTests() { return requireTests; }
        public void setRequireTests(boolean requireTests) { this.requireTests = requireTests; }
        public boolean isHasTests() { return hasTests; }
        public void setHasTests(boolean hasTests) { this.hasTests = hasTests; }
        public boolean isAllPassed() { return allPassed; }
        public void setAllPassed(boolean allPassed) { this.allPassed = allPassed; }
        public int getTestTotal() { return testTotal; }
        public void setTestTotal(int testTotal) { this.testTotal = testTotal; }
        public int getTestPassed() { return testPassed; }
        public void setTestPassed(int testPassed) { this.testPassed = testPassed; }
        public List<String> getPairedVotes() { return pairedVotes; }
        public void setPairedVotes(List<String> pairedVotes) {
            this.pairedVotes = pairedVotes == null ? new ArrayList<String>() : pairedVotes;
        }
    }

    /** Single gate result. */
    public static class GateResult {
        private String gate;
        private boolean passed;
        private Double value;
        private Double threshold;
        private String detail;
        private String evidenceType = EVIDENCE_SELF_REPORTED;

        public GateResult() {
        }

        public GateResult(String gate, boolean passed, Double value, Double threshold, String detail) {
            this.gate = gate;
            this.passed = passed;
            this.value = value;
            this.threshold = threshold;
            this.detail = detail;
        }

        public String getGate() { return gate; }
        public void setGate(String gate) { this.gate = gate; }
        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }
        public Double getValue() { return value; }
        public void setValue(Double value) { this.value = value; }
        public Double getThreshold() { return threshold; }
        public void setThreshold(Double threshold) { this.threshold = threshold; }
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
        public String getEvidenceType() { return evidenceType; }
        public void setEvidenceType(String evidenceType) {
            this.evidenceType = evidenceType == null || evidenceType.trim().isEmpty()
                    ? EVIDENCE_SELF_REPORTED : evidenceType.trim();
        }
    }

    /** Full five-gate report written onto the candidate card. */
    public static class GateReport {
        private GateResult improvement;
        private GateResult regression;
        private GateResult tests;
        private GateResult budget;
        private GateResult compare;
        private boolean allPassed;
        private List<String> failedGates = new ArrayList<String>();

        public GateResult getImprovement() { return improvement; }
        public void setImprovement(GateResult improvement) { this.improvement = improvement; }
        public GateResult getRegression() { return regression; }
        public void setRegression(GateResult regression) { this.regression = regression; }
        public GateResult getTests() { return tests; }
        public void setTests(GateResult tests) { this.tests = tests; }
        public GateResult getBudget() { return budget; }
        public void setBudget(GateResult budget) { this.budget = budget; }
        public GateResult getCompare() { return compare; }
        public void setCompare(GateResult compare) { this.compare = compare; }
        public boolean isAllPassed() { return allPassed; }
        public void setAllPassed(boolean allPassed) { this.allPassed = allPassed; }
        public List<String> getFailedGates() { return failedGates; }
        public void setFailedGates(List<String> failedGates) {
            this.failedGates = failedGates == null ? new ArrayList<String>() : failedGates;
        }
    }

    /** Evaluate five gates; external evidence types optional (default self_reported). */
    public GateReport evaluate(Metrics baseline, Metrics candidate, List<Edit> edits, Evaluation tests) {
        return evaluate(baseline, candidate, edits, tests, null);
    }

    /**
     * Evaluate five gates and stamp per-gate evidenceType from {@code evidenceTypes}.
     * Keys may be gate names or aliases (editBudget→budget, votes→compare).
     */
    public GateReport evaluate(Metrics baseline, Metrics candidate, List<Edit> edits, Evaluation tests,
                               Map<String, String> evidenceTypes) {
        Metrics base = baseline == null ? new Metrics() : baseline;
        Metrics cand = candidate == null ? new Metrics() : candidate;
        List<Edit> editList = edits == null ? Collections.<Edit>emptyList() : edits;
        Evaluation evidence = tests == null ? new Evaluation() : tests;

        GateReport report = new GateReport();
        report.setImprovement(evaluateImprovement(base, cand));
        report.setRegression(evaluateRegression(base, cand));
        report.setTests(evaluateTests(evidence));
        report.setBudget(evaluateBudget(editList));
        report.setCompare(evaluateCompare(evidence.getPairedVotes()));
        stampEvidence(report, evidenceTypes);

        List<String> failed = new ArrayList<String>();
        collectFailure(failed, report.getImprovement());
        collectFailure(failed, report.getRegression());
        collectFailure(failed, report.getTests());
        collectFailure(failed, report.getBudget());
        collectFailure(failed, report.getCompare());
        report.setFailedGates(failed);
        report.setAllPassed(failed.isEmpty());
        return report;
    }

    private static void stampEvidence(GateReport report, Map<String, String> evidenceTypes) {
        stampEvidence(report.getImprovement(), evidenceTypes, null);
        stampEvidence(report.getRegression(), evidenceTypes, null);
        stampEvidence(report.getTests(), evidenceTypes, null);
        stampEvidence(report.getBudget(), evidenceTypes, "editBudget");
        stampEvidence(report.getCompare(), evidenceTypes, "votes");
    }

    private static void stampEvidence(GateResult result, Map<String, String> evidenceTypes, String alias) {
        if (result == null) {
            return;
        }
        String value = lookupEvidence(evidenceTypes, result.getGate());
        if (value == null && alias != null) {
            value = lookupEvidence(evidenceTypes, alias);
        }
        result.setEvidenceType(value);
    }

    private static String lookupEvidence(Map<String, String> evidenceTypes, String key) {
        if (evidenceTypes == null || key == null) {
            return null;
        }
        String direct = evidenceTypes.get(key);
        if (direct != null && !direct.trim().isEmpty()) {
            return direct.trim();
        }
        for (Map.Entry<String, String> entry : evidenceTypes.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)
                    && entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                return entry.getValue().trim();
            }
        }
        return null;
    }

    /** Improvement gate: max primary-metric gain vs baseline ≥ minImprovement. */
    GateResult evaluateImprovement(Metrics baseline, Metrics candidate) {
        double best = Double.NEGATIVE_INFINITY;
        String bestName = null;

        Double gainCoverage = gain(baseline.getCoverage(), candidate.getCoverage());
        if (gainCoverage != null && gainCoverage > best) {
            best = gainCoverage;
            bestName = "coverage";
        }
        Double gainFailure = invertedGain(baseline.getFailureRate(), candidate.getFailureRate());
        if (gainFailure != null && gainFailure > best) {
            best = gainFailure;
            bestName = "failureRate";
        }
        Double gainQuality = qualityGain(baseline.getQualityScore(), candidate.getQualityScore());
        if (gainQuality != null && gainQuality > best) {
            best = gainQuality;
            bestName = "qualityScore";
        }

        if (bestName == null) {
            return new GateResult(GATE_IMPROVEMENT, false, null, minImprovement,
                    "缺少可对比的指标，无法证明改进");
        }
        boolean passed = best + 1e-9 >= minImprovement;
        return new GateResult(GATE_IMPROVEMENT, passed, Double.valueOf(best), Double.valueOf(minImprovement),
                "主指标 " + bestName + " 提升 " + fmt(best)
                        + (passed ? "，达到改进门" : "，未达改进门 " + fmt(minImprovement)));
    }

    /** Regression gate: no control metric may degrade beyond maxRegression. */
    GateResult evaluateRegression(Metrics baseline, Metrics candidate) {
        double worst = 0d;
        String worstName = null;

        Double dropCoverage = drop(baseline.getCoverage(), candidate.getCoverage());
        if (dropCoverage != null && dropCoverage > worst) {
            worst = dropCoverage;
            worstName = "coverage";
        }
        Double riseFailure = invertedDrop(baseline.getFailureRate(), candidate.getFailureRate());
        if (riseFailure != null && riseFailure > worst) {
            worst = riseFailure;
            worstName = "failureRate";
        }
        Double dropQuality = qualityDrop(baseline.getQualityScore(), candidate.getQualityScore());
        if (dropQuality != null && dropQuality > worst) {
            worst = dropQuality;
            worstName = "qualityScore";
        }

        boolean passed = worst <= maxRegression + 1e-9;
        String detail = worstName == null
                ? "无对照项退化"
                : "最大退化 " + worstName + " " + fmt(worst)
                        + (passed ? "，在回归门内" : "，超过回归门 " + fmt(maxRegression));
        return new GateResult(GATE_REGRESSION, passed, Double.valueOf(worst), Double.valueOf(maxRegression), detail);
    }

    /** Tests gate: all related tests must pass; requireTests blocks empty test evidence. */
    GateResult evaluateTests(Evaluation evidence) {
        boolean passed;
        String detail;
        double value;
        if (!evidence.isHasTests() || evidence.getTestTotal() <= 0) {
            passed = !evidence.isRequireTests();
            value = 0d;
            detail = passed ? "本单不强制测试" : "无关联测试，硬伤单不得采纳";
        } else {
            value = (double) evidence.getTestPassed() / (double) evidence.getTestTotal();
            passed = evidence.isAllPassed();
            detail = "测试 " + evidence.getTestPassed() + "/" + evidence.getTestTotal()
                    + (passed ? "，全部通过" : "，存在失败");
        }
        return new GateResult(GATE_TESTS, passed, Double.valueOf(value), Double.valueOf(1d), detail);
    }

    /** Budget gate: edit count ≤ budget and no protected-zone hit. */
    GateResult evaluateBudget(List<Edit> edits) {
        int count = edits.size();
        boolean protectedHit = false;
        for (Edit edit : edits) {
            if (edit != null && edit.isProtectedZone()) {
                protectedHit = true;
                break;
            }
        }
        boolean passed = count <= editBudget && !protectedHit;
        String detail;
        if (protectedHit) {
            detail = "触碰保护区，编辑预算门失败";
        } else {
            detail = "编辑 " + count + "/" + editBudget + (passed ? "，在预算内" : "，超出编辑预算");
        }
        return new GateResult(GATE_BUDGET, passed, Double.valueOf((double) count),
                Double.valueOf((double) editBudget), detail);
    }

    /** Compare gate: majority of paired votes must not be worse; no votes fails conservatively. */
    GateResult evaluateCompare(List<String> votes) {
        int better = 0;
        int worse = 0;
        int tie = 0;
        int valid = 0;
        if (votes != null) {
            for (String vote : votes) {
                String value = normalizeVote(vote);
                if (value == null) continue;
                valid++;
                if (VOTE_BETTER.equals(value)) better++;
                else if (VOTE_WORSE.equals(value)) worse++;
                else tie++;
            }
        }
        if (valid == 0) {
            return new GateResult(GATE_COMPARE, false, Double.valueOf(0d), Double.valueOf(0d),
                    "无有效对比票，按保守策略不通过");
        }
        boolean majorityWorse = worse * 2 > valid;
        boolean passed = !majorityWorse;
        String tally = better + ":" + worse + (tie > 0 ? ":" + tie : "");
        String detail = passed
                ? "对比多数不为更差（" + tally + "）"
                : "对比多数为更差（" + tally + "），建议回退";
        return new GateResult(GATE_COMPARE, passed, Double.valueOf((double) worse / valid),
                Double.valueOf(0.5d), detail);
    }

    static String normalizeVote(String vote) {
        if (vote == null) return null;
        String value = vote.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        if (VOTE_BETTER.equals(value) || "after".equals(value) || "improved".equals(value) || "keep".equals(value)) {
            return VOTE_BETTER;
        }
        if (VOTE_WORSE.equals(value) || "before".equals(value) || "degraded".equals(value) || "revert".equals(value)) {
            return VOTE_WORSE;
        }
        if (VOTE_TIE.equals(value) || "equal".equals(value) || "same".equals(value)) {
            return VOTE_TIE;
        }
        return null;
    }

    private static void collectFailure(List<String> failed, GateResult result) {
        if (result != null && !result.isPassed()) {
            failed.add(result.getGate());
        }
    }

    private static Double gain(Double before, Double after) {
        if (before == null || after == null) return null;
        return Double.valueOf(after.doubleValue() - before.doubleValue());
    }

    private static Double drop(Double before, Double after) {
        if (before == null || after == null) return null;
        return Double.valueOf(before.doubleValue() - after.doubleValue());
    }

    private static Double invertedGain(Double before, Double after) {
        return drop(before, after);
    }

    private static Double invertedDrop(Double before, Double after) {
        return gain(before, after);
    }

    private static Double qualityGain(Double before, Double after) {
        Double raw = gain(before, after);
        return raw == null ? null : Double.valueOf(raw.doubleValue() / 100d);
    }

    private static Double qualityDrop(Double before, Double after) {
        Double raw = drop(before, after);
        return raw == null ? null : Double.valueOf(raw.doubleValue() / 100d);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
