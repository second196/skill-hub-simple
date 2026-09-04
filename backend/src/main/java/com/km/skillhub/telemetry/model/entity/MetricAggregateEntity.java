package com.km.skillhub.telemetry.model.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class MetricAggregateEntity {
    private Long id;
    private Long scopeId;
    private String metricKey;
    private String definitionVersion;
    private OffsetDateTime windowStart;
    private OffsetDateTime windowEnd;
    private String dimensionDigest;
    private String dimensions;
    private Long sampleCount;
    private Long successCount;
    private Long errorCount;
    private BigDecimal numerator;
    private BigDecimal denominator;
    private Long inputTokens;
    private Long outputTokens;
    private BigDecimal totalCost;
    private Long latencySumMs;
    private Long latencyMaxMs;
    private String completenessStatus;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String value) { metricKey = value; }
    public String getDefinitionVersion() { return definitionVersion; }
    public void setDefinitionVersion(String value) { definitionVersion = value; }
    public OffsetDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(OffsetDateTime value) { windowStart = value; }
    public OffsetDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(OffsetDateTime value) { windowEnd = value; }
    public String getDimensionDigest() { return dimensionDigest; }
    public void setDimensionDigest(String value) { dimensionDigest = value; }
    public String getDimensions() { return dimensions; }
    public void setDimensions(String value) { dimensions = value; }
    public Long getSampleCount() { return sampleCount; }
    public void setSampleCount(Long value) { sampleCount = value; }
    public Long getSuccessCount() { return successCount; }
    public void setSuccessCount(Long value) { successCount = value; }
    public Long getErrorCount() { return errorCount; }
    public void setErrorCount(Long value) { errorCount = value; }
    public BigDecimal getNumerator() { return numerator; }
    public void setNumerator(BigDecimal value) { numerator = value; }
    public BigDecimal getDenominator() { return denominator; }
    public void setDenominator(BigDecimal value) { denominator = value; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long value) { inputTokens = value; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long value) { outputTokens = value; }
    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal value) { totalCost = value; }
    public Long getLatencySumMs() { return latencySumMs; }
    public void setLatencySumMs(Long value) { latencySumMs = value; }
    public Long getLatencyMaxMs() { return latencyMaxMs; }
    public void setLatencyMaxMs(Long value) { latencyMaxMs = value; }
    public String getCompletenessStatus() { return completenessStatus; }
    public void setCompletenessStatus(String value) { completenessStatus = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
}
