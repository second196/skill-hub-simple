package com.km.skillhub.analysis;

import java.time.OffsetDateTime;

/** ReAct trajectory step entity mapped from skill_react_step (V14). */
public class SkillReactStep {

    private Long id;
    private Long taskId;
    private Integer stepNo;
    private String thought;
    private String actionType;
    private String actionJson;
    private String observationJson;
    private Long durationMs;
    private Integer tokenEstimate;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getStepNo() { return stepNo; }
    public void setStepNo(Integer stepNo) { this.stepNo = stepNo; }
    public String getThought() { return thought; }
    public void setThought(String thought) { this.thought = thought; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getActionJson() { return actionJson; }
    public void setActionJson(String actionJson) { this.actionJson = actionJson; }
    public String getObservationJson() { return observationJson; }
    public void setObservationJson(String observationJson) { this.observationJson = observationJson; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getTokenEstimate() { return tokenEstimate; }
    public void setTokenEstimate(Integer tokenEstimate) { this.tokenEstimate = tokenEstimate; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
