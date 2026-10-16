package com.km.skillhub.workbench;

import java.util.Map;

/**
 * Skill task process event service: process nodes are durable in the DB; SSE
 * only accelerates push.
 *
 * <ul>
 *   <li>Optimize link: {@link #recordEvent} writes skill_task_event; when the
 *       task has a conversationId it also projects a skill_message
 *       (role=assistant / process_event).</li>
 *   <li>Conversation-only link: analysis orchestration without a task uses
 *       {@link #recordConversationEvent} to write skill_message only
 *       (skill_task_event.task_id FKs to skill_optimize_task, so reports cannot
 *       be task events).</li>
 *   <li>Projected messages are broadcast via {@link SkillSseHub}: DB is the
 *       source of truth; broadcast failures never break the flow (re-sync via
 *       messages?after=seq).</li>
 * </ul>
 */
public interface SkillTaskEventService {

    /**
     * Record a task process event.
     *
     * @param taskId  skill_optimize_task.id (required, FK-constrained)
     * @param stage   stage id: claimed / reading_skill / llm_requesting /
     *                llm_responded / gate_checking / waiting_input /
     *                pending_review / gated_fail / failed / advanced / …
     * @param level   info | warn | error (null → info)
     * @param payload business payload (durations, httpStatus, response digests,
     *                per-gate results); serialized to payload_json
     */
    void recordEvent(Long taskId, String stage, String level, Map<String, Object> payload);

    /**
     * Record a conversation process event (no-task scenarios such as analysis
     * orchestration): writes skill_message process_event only, never
     * skill_task_event.
     *
     * @param conversationId conversation id; null → silent no-op (no conversation, no trace)
     */
    void recordConversationEvent(Long conversationId, String stage, String level,
                                 Map<String, Object> payload);
}
