package com.km.skillhub.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Skill workbench SSE registry and broadcaster.
 *
 * <p>Registry {@code conversationId -> Set<SseEmitter>}: register hooks
 * completion/timeout/error callbacks that remove the connection; a single
 * failing send (disconnected / already completed) only removes that
 * connection and never blocks other connections or the business flow.
 * Heartbeat is a 15s comment ping ({@code :ping}) to defeat idle cuts by
 * proxies; connection hard timeout is 1 hour, after which onTimeout cleans up
 * and the client's EventSource auto-reconnects and re-syncs by seq.</p>
 */
@Component
public class SkillSseHubImpl implements SkillSseHub, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SkillSseHubImpl.class);

    /** Heartbeat interval: comment ping to keep proxies/LBs from dropping idle streams. */
    static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    /** Connection hard timeout: on expiry onTimeout unregisters; EventSource reconnects and re-syncs. */
    static final long CONNECTION_TIMEOUT_MS = 60 * 60 * 1000L;

    private final Map<Long, CopyOnWriteArraySet<SseEmitter>> subscribers =
            new ConcurrentHashMap<Long, CopyOnWriteArraySet<SseEmitter>>();

    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService heartbeat;

    public SkillSseHubImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "skill-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeat.scheduleAtFixedRate(this::heartbeatTick,
                HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public SseEmitter register(Long conversationId) {
        SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MS);
        register(conversationId, emitter);
        return emitter;
    }

    /**
     * Register a pre-built emitter (package-visible so tests can supply
     * recording emitters). Lifecycle callbacks keep the registry clean.
     */
    void register(Long conversationId, SseEmitter emitter) {
        subscribers.computeIfAbsent(conversationId,
                key -> new CopyOnWriteArraySet<SseEmitter>()).add(emitter);
        emitter.onCompletion(() -> unregister(conversationId, emitter));
        emitter.onTimeout(() -> unregister(conversationId, emitter));
        emitter.onError(error -> unregister(conversationId, emitter));
    }

    @Override
    public void unregister(Long conversationId, SseEmitter emitter) {
        CopyOnWriteArraySet<SseEmitter> set = subscribers.get(conversationId);
        if (set != null) {
            set.remove(emitter);
        }
    }

    @Override
    public void broadcast(Long conversationId, SkillMessage message) {
        if (conversationId == null || message == null) {
            return;
        }
        // Overall safety net: any broadcast failure stays internal; DB is the
        // source of truth and clients re-sync by seq.
        try {
            CopyOnWriteArraySet<SseEmitter> set = subscribers.get(conversationId);
            if (set == null || set.isEmpty()) {
                return;
            }
            String json = messageJson(message);
            for (SseEmitter emitter : set) {
                try {
                    emitter.send(SseEmitter.event().name("message").data(json));
                } catch (Exception ex) {
                    // Single-connection write failure: drop it, keep going.
                    unregister(conversationId, emitter);
                }
            }
        } catch (Exception ex) {
            log.warn("skill sse broadcast failed: conversationId={}, errorType={}",
                    conversationId, ex.getClass().getSimpleName());
        }
    }

    @Override
    public int connectionCount(Long conversationId) {
        CopyOnWriteArraySet<SseEmitter> set = subscribers.get(conversationId);
        return set == null ? 0 : set.size();
    }

    @Override
    public void destroy() {
        heartbeat.shutdownNow();
    }

    // ------------------------------------------------------------- helpers

    /** 15s heartbeat: comment ping; failing connections are dropped. */
    void heartbeatTick() {
        try {
            for (Map.Entry<Long, CopyOnWriteArraySet<SseEmitter>> entry : subscribers.entrySet()) {
                for (SseEmitter emitter : entry.getValue()) {
                    try {
                        emitter.send(SseEmitter.event().comment("ping"));
                    } catch (Exception ex) {
                        unregister(entry.getKey(), emitter);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("skill sse heartbeat failed: errorType={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * Full message JSON: fields aligned with
     * {@code SkillConversationController#messageView}
     * (WorkbenchMessage contract, see frontend/src/types/skillWorkbench.ts).
     */
    private String messageJson(SkillMessage message) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", message.getId());
        view.put("conversationId", message.getConversationId());
        view.put("seq", message.getSeq());
        view.put("role", message.getRole());
        view.put("msgType", message.getMsgType());
        view.put("content", message.getContent());
        view.put("relatedTaskId", message.getRelatedTaskId());
        view.put("relatedCandidateId", message.getRelatedCandidateId());
        view.put("createdAt", message.getCreatedAt() == null ? null : message.getCreatedAt().toString());
        try {
            return objectMapper.writeValueAsString(view);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
