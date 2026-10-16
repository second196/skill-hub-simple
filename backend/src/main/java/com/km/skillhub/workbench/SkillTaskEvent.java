package com.km.skillhub.workbench;

import java.time.OffsetDateTime;

/**
 * Task process event (skill_task_event, V13): durable stage trace for the
 * optimize pipeline (LLM duration, gate results, failure reasons, ...).
 */
public class SkillTaskEvent {

    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_WARN = "warn";
    public static final String LEVEL_ERROR = "error";

    private Long id;
    private Long taskId;
    /** claimed / reading_skill / llm_requesting / gate_checking / waiting_input / failed / ... */
    private String stage;
    private String level;
    /** JSON payload text; raw responses truncated on write. */
    private String payloadJson;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
