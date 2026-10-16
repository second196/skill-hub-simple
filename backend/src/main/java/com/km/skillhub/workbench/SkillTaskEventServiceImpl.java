package com.km.skillhub.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill task process event implementation.
 *
 * <p>skill_task_event.task_id FKs to skill_optimize_task, so the analysis link
 * (no task) only uses {@link #recordConversationEvent} to project
 * skill_message. Conversation projection seq uses the same
 * SkillConversationRepository#nextMessageSeq (max(seq)+1; single-conversation
 * concurrency is low, no unique constraint).</p>
 */
@Service
public class SkillTaskEventServiceImpl implements SkillTaskEventService {

    private static final Logger log = LoggerFactory.getLogger(SkillTaskEventServiceImpl.class);

    private final SkillConversationRepository repository;
    private final ObjectMapper objectMapper;
    /** Optional: old test constructors may skip SSE broadcast; broadcast failures never break the flow. */
    private final SkillSseHub sseHub;

    public SkillTaskEventServiceImpl(SkillConversationRepository repository,
                                     ObjectMapper objectMapper) {
        this(repository, objectMapper, null);
    }

    @Autowired
    public SkillTaskEventServiceImpl(SkillConversationRepository repository,
                                     ObjectMapper objectMapper,
                                     SkillSseHub sseHub) {
        this.repository = repository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.sseHub = sseHub;
    }

    @Override
    public void recordEvent(Long taskId, String stage, String level, Map<String, Object> payload) {
        if (taskId == null || isBlank(stage)) {
            return;
        }
        String normalizedStage = stage.trim();
        String normalizedLevel = normalizeLevel(level);
        String payloadJson = toJson(payload);

        SkillTaskEvent event = new SkillTaskEvent();
        event.setTaskId(taskId);
        event.setStage(normalizedStage);
        event.setLevel(normalizedLevel);
        event.setPayloadJson(payloadJson);
        repository.insertTaskEvent(event);

        Long conversationId = repository.findTaskConversationId(taskId);
        if (conversationId != null) {
            projectMessage(conversationId, taskId, null,
                    normalizedStage, normalizedLevel, payload);
        }
    }

    @Override
    public void recordConversationEvent(Long conversationId, String stage, String level,
                                        Map<String, Object> payload) {
        if (conversationId == null || isBlank(stage)) {
            return;
        }
        projectMessage(conversationId, null, null, stage.trim(), normalizeLevel(level), payload);
    }

    // ------------------------------------------------------------- helpers

    private void projectMessage(Long conversationId, Long relatedTaskId, Long relatedCandidateId,
                                String stage, String level, Map<String, Object> payload) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<String, Object>();
            envelope.put("stage", stage);
            envelope.put("level", level);
            envelope.put("payload", payload == null ? Collections.emptyMap() : payload);

            SkillMessage message = new SkillMessage();
            message.setConversationId(conversationId);
            message.setSeq(Integer.valueOf(repository.nextMessageSeq(conversationId)));
            message.setRole(SkillMessage.ROLE_ASSISTANT);
            message.setMsgType(SkillMessage.TYPE_PROCESS_EVENT);
            message.setContent(toJson(envelope));
            message.setRelatedTaskId(relatedTaskId);
            message.setRelatedCandidateId(relatedCandidateId);
            SkillMessage saved = repository.insertMessage(message);
            // Broadcast after persist: SSE only accelerates; re-sync by seq on disconnect
            broadcast(saved);
        } catch (RuntimeException ex) {
            // Event persist failure never breaks the task flow; skill_task_event row (when
            // any) remains the compensation source.
            log.warn("skill process event projection failed: conversationId={}, stage={}, errorType={}",
                    conversationId, stage, ex.getClass().getSimpleName());
        }
    }

    /** Broadcast to the conversation's SSE connections; failure never breaks the main flow. */
    private void broadcast(SkillMessage message) {
        if (sseHub == null || message == null || message.getConversationId() == null) {
            return;
        }
        try {
            sseHub.broadcast(message.getConversationId(), message);
        } catch (RuntimeException ex) {
            log.warn("skill sse broadcast failed: conversationId={}, errorType={}",
                    message.getConversationId(), ex.getClass().getSimpleName());
        }
    }

    private static String normalizeLevel(String level) {
        String value = level == null ? "" : level.trim().toLowerCase();
        if (SkillTaskEvent.LEVEL_WARN.equals(value) || SkillTaskEvent.LEVEL_ERROR.equals(value)) {
            return value;
        }
        return SkillTaskEvent.LEVEL_INFO;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Collections.emptyMap() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
