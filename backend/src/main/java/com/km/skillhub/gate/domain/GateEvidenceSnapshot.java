package com.km.skillhub.gate.domain;

public class GateEvidenceSnapshot {
    private final String evidenceType;
    private final String result;
    private final int validCases;
    private final int validCalls;
    private final double errorRate;
    private final double baselineErrorRate;
    private final double score;
    private final double baselineScore;
    private final boolean highRisk;
    private final boolean unauthorizedChange;
    private final boolean runtimeCompatible;
    private final boolean regressionPassed;
    private final boolean expired;

    public GateEvidenceSnapshot(String evidenceType, String result, int validCases, int validCalls,
                                double errorRate, double baselineErrorRate, double score, double baselineScore,
                                boolean highRisk, boolean unauthorizedChange, boolean runtimeCompatible,
                                boolean regressionPassed, boolean expired) {
        this.evidenceType = evidenceType; this.result = result; this.validCases = validCases; this.validCalls = validCalls;
        this.errorRate = errorRate; this.baselineErrorRate = baselineErrorRate; this.score = score;
        this.baselineScore = baselineScore; this.highRisk = highRisk; this.unauthorizedChange = unauthorizedChange;
        this.runtimeCompatible = runtimeCompatible; this.regressionPassed = regressionPassed; this.expired = expired;
    }
    public String getEvidenceType() { return evidenceType; }
    public String getResult() { return result; }
    public int getValidCases() { return validCases; }
    public int getValidCalls() { return validCalls; }
    public double getErrorRate() { return errorRate; }
    public double getBaselineErrorRate() { return baselineErrorRate; }
    public double getScore() { return score; }
    public double getBaselineScore() { return baselineScore; }
    public boolean isHighRisk() { return highRisk; }
    public boolean isUnauthorizedChange() { return unauthorizedChange; }
    public boolean isRuntimeCompatible() { return runtimeCompatible; }
    public boolean isRegressionPassed() { return regressionPassed; }
    public boolean isExpired() { return expired; }
}
