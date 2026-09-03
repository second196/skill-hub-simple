package com.km.skillhub.gate.model;

public class GrayObservation {
    private String versionDigest;
    private int instanceCount;
    private int validCalls;
    private long observedSeconds;
    private double errorRate;
    private double baselineErrorRate;
    private double score;
    private double baselineScore;
    private boolean highRisk;
    private boolean seriousIssue;
    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String versionDigest) { this.versionDigest = versionDigest; }
    public int getInstanceCount() { return instanceCount; }
    public void setInstanceCount(int instanceCount) { this.instanceCount = instanceCount; }
    public int getValidCalls() { return validCalls; }
    public void setValidCalls(int validCalls) { this.validCalls = validCalls; }
    public long getObservedSeconds() { return observedSeconds; }
    public void setObservedSeconds(long observedSeconds) { this.observedSeconds = observedSeconds; }
    public double getErrorRate() { return errorRate; }
    public void setErrorRate(double errorRate) { this.errorRate = errorRate; }
    public double getBaselineErrorRate() { return baselineErrorRate; }
    public void setBaselineErrorRate(double baselineErrorRate) { this.baselineErrorRate = baselineErrorRate; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public double getBaselineScore() { return baselineScore; }
    public void setBaselineScore(double baselineScore) { this.baselineScore = baselineScore; }
    public boolean isHighRisk() { return highRisk; }
    public void setHighRisk(boolean highRisk) { this.highRisk = highRisk; }
    public boolean isSeriousIssue() { return seriousIssue; }
    public void setSeriousIssue(boolean seriousIssue) { this.seriousIssue = seriousIssue; }
}
