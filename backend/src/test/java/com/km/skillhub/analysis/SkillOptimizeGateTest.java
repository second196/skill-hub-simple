package com.km.skillhub.analysis;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Five-gate threshold logic (improvement ≥ 0.02 / regression ≤ 0.02 / tests pass /
 * budget ≤ 4 / paired compare not majority-worse).
 */
class SkillOptimizeGateTest {

    private final SkillOptimizeGate gate = new SkillOptimizeGate();

    @Test
    void improvementPassesWhenPrimaryMetricGainMeetsThreshold() {
        SkillOptimizeGate.Metrics baseline = new SkillOptimizeGate.Metrics(0.50d, 0.20d, 70.0d);
        SkillOptimizeGate.Metrics candidate = new SkillOptimizeGate.Metrics(0.55d, 0.20d, 70.0d);
        SkillOptimizeGate.GateReport report = gate.evaluate(baseline, candidate,
                oneEdit(), passingTests());
        assertTrue(report.getImprovement().isPassed(), report.getImprovement().getDetail());
        assertEquals(0.05d, report.getImprovement().getValue(), 1e-9);
        assertEquals(0.02d, report.getImprovement().getThreshold(), 1e-9);
    }

    @Test
    void improvementFailsWhenGainBelowThreshold() {
        SkillOptimizeGate.Metrics baseline = new SkillOptimizeGate.Metrics(0.50d, null, null);
        SkillOptimizeGate.Metrics candidate = new SkillOptimizeGate.Metrics(0.51d, null, null);
        SkillOptimizeGate.GateResult result = gate.evaluate(baseline, candidate,
                oneEdit(), passingTests()).getImprovement();
        assertFalse(result.isPassed());
        assertTrue(result.getDetail().contains("未达改进门"));
    }

    @Test
    void improvementFailsWhenNoComparableMetrics() {
        SkillOptimizeGate.GateResult result = gate.evaluate(
                new SkillOptimizeGate.Metrics(), new SkillOptimizeGate.Metrics(),
                oneEdit(), passingTests()).getImprovement();
        assertFalse(result.isPassed());
    }

    @Test
    void regressionFailsWhenControlDropsBeyondThreshold() {
        SkillOptimizeGate.Metrics baseline = new SkillOptimizeGate.Metrics(0.80d, 0.10d, 80.0d);
        SkillOptimizeGate.Metrics candidate = new SkillOptimizeGate.Metrics(0.70d, 0.10d, 80.0d);
        SkillOptimizeGate.GateResult result = gate.evaluate(baseline, candidate,
                oneEdit(), passingTests()).getRegression();
        assertFalse(result.isPassed());
        assertEquals(0.10d, result.getValue(), 1e-9);
    }

    @Test
    void regressionPassesWhenDropWithinBudget() {
        SkillOptimizeGate.Metrics baseline = new SkillOptimizeGate.Metrics(0.80d, null, null);
        SkillOptimizeGate.Metrics candidate = new SkillOptimizeGate.Metrics(0.79d, null, null);
        SkillOptimizeGate.GateResult result = gate.evaluate(baseline, candidate,
                oneEdit(), passingTests()).getRegression();
        assertTrue(result.isPassed());
    }

    @Test
    void testsGateRequiresAllPassedWhenRequireTests() {
        SkillOptimizeGate.Evaluation failing = SkillOptimizeGate.Evaluation.testsPassed(3, 2,
                Collections.singletonList("better"));
        SkillOptimizeGate.GateResult result = gate.evaluate(
                new SkillOptimizeGate.Metrics(0.5d, 0.1d, 70d),
                new SkillOptimizeGate.Metrics(0.6d, 0.1d, 72d),
                oneEdit(), failing).getTests();
        assertFalse(result.isPassed());

        SkillOptimizeGate.Evaluation empty = new SkillOptimizeGate.Evaluation();
        empty.setRequireTests(true);
        empty.setHasTests(false);
        assertFalse(gate.evaluate(new SkillOptimizeGate.Metrics(0.5d, 0.1d, 70d),
                new SkillOptimizeGate.Metrics(0.6d, 0.1d, 72d), oneEdit(), empty).getTests().isPassed());
    }

    @Test
    void budgetGateFailsOverFourEditsOrProtectedZone() {
        List<SkillOptimizeGate.Edit> five = Arrays.asList(
                new SkillOptimizeGate.Edit("a.md", "append", false),
                new SkillOptimizeGate.Edit("b.md", "append", false),
                new SkillOptimizeGate.Edit("c.md", "append", false),
                new SkillOptimizeGate.Edit("d.md", "append", false),
                new SkillOptimizeGate.Edit("e.md", "append", false));
        SkillOptimizeGate.GateResult over = gate.evaluate(passingMetrics(), passingMetrics(),
                five, passingTests()).getBudget();
        assertFalse(over.isPassed());

        List<SkillOptimizeGate.Edit> protectedHit = Collections.singletonList(
                new SkillOptimizeGate.Edit("core.md", "replace", true));
        assertFalse(gate.evaluate(passingMetrics(), passingMetrics(),
                protectedHit, passingTests()).getBudget().isPassed());

        assertTrue(gate.evaluate(passingMetrics(), passingMetrics(),
                oneEdit(), passingTests()).getBudget().isPassed());
    }

    @Test
    void compareGateFailsOnNoVotesOrMajorityWorse() {
        assertFalse(gate.evaluate(passingMetrics(), passingMetrics(),
                oneEdit(), SkillOptimizeGate.Evaluation.testsPassed(1, 1,
                        Collections.<String>emptyList())).getCompare().isPassed());

        assertFalse(gate.evaluate(passingMetrics(), passingMetrics(),
                oneEdit(), SkillOptimizeGate.Evaluation.testsPassed(1, 1,
                        Arrays.asList("worse", "worse", "better"))).getCompare().isPassed());

        assertTrue(gate.evaluate(passingMetrics(), passingMetrics(),
                oneEdit(), SkillOptimizeGate.Evaluation.testsPassed(1, 1,
                        Arrays.asList("better", "worse", "tie"))).getCompare().isPassed());
    }

    @Test
    void allPassedWhenEveryGatePassesAndFailedGatesListedOtherwise() {
        SkillOptimizeGate.GateReport ok = gate.evaluate(
                new SkillOptimizeGate.Metrics(0.50d, 0.10d, 70.0d),
                new SkillOptimizeGate.Metrics(0.60d, 0.08d, 75.0d),
                oneEdit(), SkillOptimizeGate.Evaluation.testsPassed(2, 2,
                        Arrays.asList("better", "better", "tie")));
        assertTrue(ok.isAllPassed());
        assertTrue(ok.getFailedGates().isEmpty());

        SkillOptimizeGate.GateReport bad = gate.evaluate(
                new SkillOptimizeGate.Metrics(0.5d, null, null),
                new SkillOptimizeGate.Metrics(0.5d, null, null),
                oneEdit(), passingTests());
        assertFalse(bad.isAllPassed());
        assertTrue(bad.getFailedGates().contains("improvement"));
    }

    @Test
    void customThresholdsAreHonoured() {
        SkillOptimizeGate strict = new SkillOptimizeGate(0.10d, 0.01d, 2);
        SkillOptimizeGate.Metrics baseline = new SkillOptimizeGate.Metrics(0.50d, null, null);
        SkillOptimizeGate.Metrics candidate = new SkillOptimizeGate.Metrics(0.55d, null, null);
        SkillOptimizeGate.GateReport report = strict.evaluate(baseline, candidate,
                Arrays.asList(
                        new SkillOptimizeGate.Edit("a", "append", false),
                        new SkillOptimizeGate.Edit("b", "append", false),
                        new SkillOptimizeGate.Edit("c", "append", false)),
                passingTests());
        assertFalse(report.getImprovement().isPassed());
        assertFalse(report.getBudget().isPassed());
        assertEquals(2, report.getBudget().getThreshold().intValue());
    }

    private static SkillOptimizeGate.Metrics passingMetrics() {
        return new SkillOptimizeGate.Metrics(0.60d, 0.05d, 80.0d);
    }

    private static List<SkillOptimizeGate.Edit> oneEdit() {
        return Collections.singletonList(new SkillOptimizeGate.Edit("SKILL.md", "append", false));
    }

    private static SkillOptimizeGate.Evaluation passingTests() {
        return SkillOptimizeGate.Evaluation.testsPassed(1, 1,
                Arrays.asList("better", "better", "tie"));
    }

}
