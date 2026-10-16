package com.km.skillhub.analysis;

import java.time.OffsetDateTime;

/** Optimize candidate entity mapped from skill_optimize_candidate (V12/V14). */
public class SkillOptimizeCandidate {

    public static final String DECISION_PENDING = "pending";
    public static final String DECISION_ADOPTED = "adopted";
    public static final String DECISION_REJECTED = "rejected";

    private Long id;
    private Long taskId;
    private String summary;
    private String diffText;
    private String editsJson;
    private String gatesJson;
    private String pairedJson;
    private String decision;
    private String decisionReason;
    private String decidedBy;
    private OffsetDateTime decidedAt;
    private Long resultVersionId;
    private String resultVersionLabel;
    private String coverageJson;
    private String evidenceSummaryJson;
    private Boolean truncated;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDiffText() { return diffText; }
    public void setDiffText(String diffText) { this.diffText = diffText; }
    public String getEditsJson() { return editsJson; }
    public void setEditsJson(String editsJson) { this.editsJson = editsJson; }
    public String getGatesJson() { return gatesJson; }
    public void setGatesJson(String gatesJson) { this.gatesJson = gatesJson; }
    public String getPairedJson() { return pairedJson; }
    public void setPairedJson(String pairedJson) { this.pairedJson = pairedJson; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime decidedAt) { this.decidedAt = decidedAt; }
    public Long getResultVersionId() { return resultVersionId; }
    public void setResultVersionId(Long resultVersionId) { this.resultVersionId = resultVersionId; }
    public String getResultVersionLabel() { return resultVersionLabel; }
    public void setResultVersionLabel(String resultVersionLabel) { this.resultVersionLabel = resultVersionLabel; }
    public String getCoverageJson() { return coverageJson; }
    public void setCoverageJson(String coverageJson) { this.coverageJson = coverageJson; }
    public String getEvidenceSummaryJson() { return evidenceSummaryJson; }
    public void setEvidenceSummaryJson(String evidenceSummaryJson) { this.evidenceSummaryJson = evidenceSummaryJson; }
    public Boolean getTruncated() { return truncated; }
    public void setTruncated(Boolean truncated) { this.truncated = truncated; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
