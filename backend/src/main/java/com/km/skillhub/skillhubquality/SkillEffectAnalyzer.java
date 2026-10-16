package com.km.skillhub.skillhubquality;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * L3 使用效果分析：以 {@code ObservationRepository} 的行为指标
 * （errorRate / reloadRate / checklistCoverage）为输入，输出覆盖率、
 * 失败率、成本三项重点指标与得分。
 *
 * <p>无观测数据（calls=0）返回 null，由服务层走与 kmplm 一致的降级结构
 * （score=null、sampleInsufficient=true、problems=[暂无链路数据]）。</p>
 */
@Service
public class SkillEffectAnalyzer {

    /**
     * Behavior metrics of one skill version pulled from the observation store.
     * {@code available} means the version has at least one observed skill step.
     */
    public static class ObservationMetrics {
        private final int calls;
        private final int skillTurns;
        private final int sessions;
        private final int errors;
        private final int reloadTurns;
        private final double errorRate;
        private final double reloadRate;
        private final double checklistCoverage;
        private final long avgTokens;

        public ObservationMetrics(int calls, int skillTurns, int sessions, int errors, int reloadTurns,
                                  double errorRate, double reloadRate, double checklistCoverage,
                                  long avgTokens) {
            this.calls = Math.max(0, calls);
            this.skillTurns = Math.max(0, skillTurns);
            this.sessions = Math.max(0, sessions);
            this.errors = Math.max(0, errors);
            this.reloadTurns = Math.max(0, reloadTurns);
            this.errorRate = clamp01(errorRate);
            this.reloadRate = clamp01(reloadRate);
            this.checklistCoverage = checklistCoverage;
            this.avgTokens = Math.max(0L, avgTokens);
        }

        /** No observed skill step for this version. */
        public static ObservationMetrics unavailable() {
            return new ObservationMetrics(0, 0, 0, 0, 0, 0d, 0d, -1d, 0L);
        }

        public boolean isAvailable() { return calls > 0; }
        public int getCalls() { return calls; }
        public int getSkillTurns() { return skillTurns; }
        public int getSessions() { return sessions; }
        public int getErrors() { return errors; }
        public int getReloadTurns() { return reloadTurns; }
        public double getErrorRate() { return errorRate; }
        public double getReloadRate() { return reloadRate; }
        /** 0..1, or -1 when the skill has no checklist contract yet. */
        public double getChecklistCoverage() { return checklistCoverage; }
        public long getAvgTokens() { return avgTokens; }

        private static double clamp01(double value) {
            if (value < 0d) return 0d;
            if (value > 1d) return 1d;
            return value;
        }
    }

    /** L3 result: three metrics + score. */
    public static class EffectReport {
        private double coverage;
        private double failureRate;
        /** none | normal | high */
        private String costLevel = "none";
        private int score;
        private boolean sampleOk;
        private int sampleCount;
        private double errorRate;
        private double reloadRate;
        private Double completionRate;

        public double getCoverage() { return coverage; }
        public void setCoverage(double coverage) { this.coverage = coverage; }
        public double getFailureRate() { return failureRate; }
        public void setFailureRate(double failureRate) { this.failureRate = failureRate; }
        public String getCostLevel() { return costLevel; }
        public void setCostLevel(String costLevel) { this.costLevel = costLevel; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public boolean isSampleOk() { return sampleOk; }
        public void setSampleOk(boolean sampleOk) { this.sampleOk = sampleOk; }
        public int getSampleCount() { return sampleCount; }
        public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }
        public double getReloadRate() { return reloadRate; }
        public void setReloadRate(double reloadRate) { this.reloadRate = reloadRate; }
        /** null when checklist coverage was never evaluated. */
        public Double getCompletionRate() { return completionRate; }
        public void setCompletionRate(Double completionRate) { this.completionRate = completionRate; }
    }

    private final int minSamples;
    private final long highTokens;

    public SkillEffectAnalyzer(@Value("${skillhub.quality.l3.min-samples:5}") int minSamples,
                               @Value("${skillhub.quality.l3.high-tokens:20000}") long highTokens) {
        this.minSamples = minSamples <= 0 ? 5 : minSamples;
        this.highTokens = highTokens <= 0L ? 20000L : highTokens;
    }

    /**
     * Aggregate behavior metrics into the L3 report; null when the version has
     * no observed usage (callers degrade instead of failing).
     */
    public EffectReport analyze(ObservationMetrics metrics) {
        if (metrics == null || !metrics.isAvailable()) {
            return null;
        }
        double coverage = metrics.getChecklistCoverage() < 0d ? 0d : metrics.getChecklistCoverage();
        double failureRate = metrics.getErrorRate();
        String costLevel;
        if (metrics.getAvgTokens() <= 0L) {
            costLevel = "none";
        } else {
            costLevel = metrics.getAvgTokens() > highTokens ? "high" : "normal";
        }

        EffectReport report = new EffectReport();
        report.setCoverage(coverage);
        report.setFailureRate(failureRate);
        report.setCostLevel(costLevel);
        report.setScore(computeScore(coverage, failureRate, costLevel));
        report.setSampleOk(metrics.getCalls() >= minSamples);
        report.setSampleCount(metrics.getCalls());
        report.setErrorRate(metrics.getErrorRate());
        report.setReloadRate(metrics.getReloadRate());
        report.setCompletionRate(metrics.getChecklistCoverage() < 0d
                ? null : Double.valueOf(round2(metrics.getChecklistCoverage())));
        return report;
    }

    /**
     * score = wC×覆盖率×100 + wF×(1−失败率)×100 + wCost×成本分；
     * 成本无数据时权重回摊到覆盖率与失败率（0.5 / 0.5）。
     */
    private int computeScore(double coverage, double failureRate, String costLevel) {
        double coverageScore = coverage * 100d;
        double failureScore = (1d - failureRate) * 100d;
        double wCoverage = 0.4d;
        double wFailure = 0.4d;
        double wCost = 0.2d;
        double costScore;
        if ("none".equals(costLevel)) {
            costScore = 0d;
            wCost = 0d;
            wCoverage = 0.5d;
            wFailure = 0.5d;
        } else if ("high".equals(costLevel)) {
            costScore = 40d;
        } else {
            costScore = 100d;
        }
        double score = wCoverage * coverageScore + wFailure * failureScore + wCost * costScore;
        int rounded = (int) Math.round(score);
        if (rounded < 0) rounded = 0;
        if (rounded > 100) rounded = 100;
        return rounded;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
