package com.km.skillhub.gate.model;

import java.time.OffsetDateTime;

public class GateEvidenceRequest {
    private String versionDigest;
    private String evidenceType;
    private String result;
    private String producerType;
    private String producerId;
    private String evidenceUri;
    private String evidenceDigest;
    private GateMetrics metrics;
    private OffsetDateTime generatedAt;
    private OffsetDateTime expiresAt;

    public String getVersionDigest() { return versionDigest; }
    public void setVersionDigest(String versionDigest) { this.versionDigest = versionDigest; }
    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String evidenceType) { this.evidenceType = evidenceType; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getProducerType() { return producerType; }
    public void setProducerType(String producerType) { this.producerType = producerType; }
    public String getProducerId() { return producerId; }
    public void setProducerId(String producerId) { this.producerId = producerId; }
    public String getEvidenceUri() { return evidenceUri; }
    public void setEvidenceUri(String evidenceUri) { this.evidenceUri = evidenceUri; }
    public String getEvidenceDigest() { return evidenceDigest; }
    public void setEvidenceDigest(String evidenceDigest) { this.evidenceDigest = evidenceDigest; }
    public GateMetrics getMetrics() { return metrics; }
    public void setMetrics(GateMetrics metrics) { this.metrics = metrics; }
    public OffsetDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(OffsetDateTime generatedAt) { this.generatedAt = generatedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }

    public static class GateMetrics {
        private Integer validCases;
        private Integer validCalls;
        private Double errorRate;
        private Double baselineErrorRate;
        private Double score;
        private Double baselineScore;
        private Boolean highRisk;
        private Boolean unauthorizedChange;
        private Boolean runtimeCompatible;
        private Boolean regressionPassed;
        public Integer getValidCases() { return validCases; }
        public void setValidCases(Integer validCases) { this.validCases = validCases; }
        public Integer getValidCalls() { return validCalls; }
        public void setValidCalls(Integer validCalls) { this.validCalls = validCalls; }
        public Double getErrorRate() { return errorRate; }
        public void setErrorRate(Double errorRate) { this.errorRate = errorRate; }
        public Double getBaselineErrorRate() { return baselineErrorRate; }
        public void setBaselineErrorRate(Double baselineErrorRate) { this.baselineErrorRate = baselineErrorRate; }
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
        public Double getBaselineScore() { return baselineScore; }
        public void setBaselineScore(Double baselineScore) { this.baselineScore = baselineScore; }
        public Boolean getHighRisk() { return highRisk; }
        public void setHighRisk(Boolean highRisk) { this.highRisk = highRisk; }
        public Boolean getUnauthorizedChange() { return unauthorizedChange; }
        public void setUnauthorizedChange(Boolean unauthorizedChange) { this.unauthorizedChange = unauthorizedChange; }
        public Boolean getRuntimeCompatible() { return runtimeCompatible; }
        public void setRuntimeCompatible(Boolean runtimeCompatible) { this.runtimeCompatible = runtimeCompatible; }
        public Boolean getRegressionPassed() { return regressionPassed; }
        public void setRegressionPassed(Boolean regressionPassed) { this.regressionPassed = regressionPassed; }
    }
}
