package com.km.skillhub.analysis;

import java.time.OffsetDateTime;

/**
 * Optimize task entity mapped from skill_optimize_task (V12/V13/V14).
 * Status machine: queued → running → gated_pass/pending_review → adopted;
 * running → waiting_input (stepwise) | gated_fail → rejected / queued.
 */
public class SkillOptimizeTask {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_WAITING_INPUT = "waiting_input";
    public static final String STATUS_GATED_PASS = "gated_pass";
    public static final String STATUS_PENDING_REVIEW = "pending_review";
    public static final String STATUS_GATED_FAIL = "gated_fail";
    public static final String STATUS_ADOPTED = "adopted";
    public static final String STATUS_REJECTED = "rejected";

    public static final String STAGE_QUEUED = "queued";
    public static final String STAGE_READING_SKILL = "reading_skill";
    public static final String STAGE_GENERATING_CANDIDATE = "generating_candidate";
    public static final String STAGE_SAVING_CANDIDATE = "saving_candidate";
    public static final String STAGE_RUNNING_GATES = "running_gates";
    public static final String STAGE_PENDING_REVIEW = "pending_review";
    public static final String STAGE_FAILED = "failed";

    private Long id;
    private Long skillId;
    private Long versionId;
    private String mode;
    private String status;
    private String priorityListJson;
    private String inputsJson;
    private String failReason;
    private String runMode;
    private String targetIssueIdsJson;
    private String progressStage;
    private Integer progressPercent;
    private String progressMessage;
    private String targetScopeJson;
    private String reactStateJson;
    private String reactMode;
    private String engine;
    private Long conversationId;
    private String createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSkillId() { return skillId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPriorityListJson() { return priorityListJson; }
    public void setPriorityListJson(String priorityListJson) { this.priorityListJson = priorityListJson; }
    public String getInputsJson() { return inputsJson; }
    public void setInputsJson(String inputsJson) { this.inputsJson = inputsJson; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getRunMode() { return runMode; }
    public void setRunMode(String runMode) { this.runMode = runMode; }
    public String getTargetIssueIdsJson() { return targetIssueIdsJson; }
    public void setTargetIssueIdsJson(String targetIssueIdsJson) { this.targetIssueIdsJson = targetIssueIdsJson; }
    public String getProgressStage() { return progressStage; }
    public void setProgressStage(String progressStage) { this.progressStage = progressStage; }
    public Integer getProgressPercent() { return progressPercent; }
    public void setProgressPercent(Integer progressPercent) { this.progressPercent = progressPercent; }
    public String getProgressMessage() { return progressMessage; }
    public void setProgressMessage(String progressMessage) { this.progressMessage = progressMessage; }
    public String getTargetScopeJson() { return targetScopeJson; }
    public void setTargetScopeJson(String targetScopeJson) { this.targetScopeJson = targetScopeJson; }
    public String getReactStateJson() { return reactStateJson; }
    public void setReactStateJson(String reactStateJson) { this.reactStateJson = reactStateJson; }
    public String getReactMode() { return reactMode; }
    public void setReactMode(String reactMode) { this.reactMode = reactMode; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
