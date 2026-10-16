package com.km.skillhub.workbench;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Skill workbench conversation SSE registry and broadcaster.
 *
 * <p><strong>Events are durable in the DB; SSE only accelerates push.</strong>
 * This component forwards messages that were already written to
 * {@code skill_message}. After a disconnect or lost event, clients re-sync via
 * {@code GET /api/observations/conversations/{id}/messages?after=seq}.</p>
 *
 * <p>Contract: {@link #broadcast} must not throw — a single failing
 * connection is only removed; broadcast failures never break the business
 * flow.</p>
 */
public interface SkillSseHub {

    /** Register one SSE connection for the conversation (event name {@code message}). */
    SseEmitter register(Long conversationId);

    /** Unregister a connection (completion/timeout/error callbacks and tests). */
    void unregister(Long conversationId, SseEmitter emitter);

    /** Broadcast an already-persisted message to every live connection of the conversation. */
    void broadcast(Long conversationId, SkillMessage message);

    /** Live connection count for the conversation (diagnostics/tests). */
    int connectionCount(Long conversationId);
}
