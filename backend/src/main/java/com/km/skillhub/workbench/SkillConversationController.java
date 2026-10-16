package com.km.skillhub.workbench;

import com.km.skillhub.analysis.SkillOptimizeService;
import com.km.skillhub.analysis.SkillOptimizeTask;
import com.km.skillhub.analysis.SkillOptimizeTaskJob;
import com.km.skillhub.skill.SkillApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill workbench conversation REST (mounted on the observation domain
 * {@code /api/observations}).
 *
 * <p>Response fields follow the frontend contract
 * {@code frontend/src/types/skillWorkbench.ts}; errors return
 * {@code {message, code}} with 4xx, style matching SkillQualityController /
 * SkillOptimizeController.</p>
 */
@RestController
@RequestMapping("/api/observations")
public class SkillConversationController {

    private final SkillConversationService conversationService;

    private final SkillSseHub sseHub;

    private final SkillTaskEventService taskEventService;

    private final SkillOptimizeService optimizeService;

    private final SkillOptimizeTaskJob optimizeTaskJob;

    public SkillConversationController(SkillConversationService conversationService,
                                       SkillSseHub sseHub,
                                       SkillTaskEventService taskEventService,
                                       SkillOptimizeService optimizeService,
                                       SkillOptimizeTaskJob optimizeTaskJob) {
        this.conversationService = conversationService;
        this.sseHub = sseHub;
        this.taskEventService = taskEventService;
        this.optimizeService = optimizeService;
        this.optimizeTaskJob = optimizeTaskJob;
    }

    /** Create a conversation bound to Skill@version (server writes welcome + quick-action card). */
    @PostMapping("/skills/{slug}/versions/{versionId}/conversations")
    public ResponseEntity<Object> create(@PathVariable String slug,
                                         @PathVariable Long versionId) {
        try {
            SkillConversation conversation = conversationService.createConversation(slug, versionId);
            return ResponseEntity.ok(summaryView(conversation));
        } catch (SkillApiException ex) {
            return errorFor(ex);
        }
    }

    /** Conversation history (filtered by slug + optional versionId, updated_at descending). */
    @GetMapping("/skills/{slug}/conversations")
    public ResponseEntity<Object> list(@PathVariable String slug,
                                       @RequestParam(required = false) Long versionId) {
        try {
            List<SkillConversation> conversations = conversationService.listConversations(slug, versionId);
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (SkillConversation conversation : conversations) {
                items.add(summaryView(conversation));
            }
            return ResponseEntity.ok(items);
        } catch (SkillApiException ex) {
            return errorFor(ex);
        }
    }

    /**
     * Conversation messages (seq ascending).
     * {@code after} is an exclusive lower bound: only messages with seq &gt; after; omit for all.
     */
    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<Object> messages(@PathVariable Long id,
                                           @RequestParam(required = false) Integer after) {
        try {
            List<SkillMessage> messages = conversationService.listMessages(id, after);
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (SkillMessage message : messages) {
                items.add(messageView(message));
            }
            return ResponseEntity.ok(items);
        } catch (SkillApiException ex) {
            return errorFor(ex);
        }
    }

    /**
     * Conversation event stream (SSE). Event name {@code message}; data is the
     * full message JSON (WorkbenchMessage: id / conversationId / seq / role /
     * msgType / content / relatedTaskId / relatedCandidateId / createdAt),
     * with a 15s comment ping heartbeat.
     *
     * <p><strong>Events are durable in the DB; SSE only accelerates.</strong>
     * After a disconnect or a lost event, clients re-sync via
     * {@code GET /api/observations/conversations/{id}/messages?after=seq}.</p>
     */
    @GetMapping(value = "/conversations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id) {
        return sseHub.register(id);
    }

    /** Persist a user message (seq auto-increment + SSE broadcast). */
    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<Object> send(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        try {
            Object content = body == null ? null : body.get("content");
            SkillMessage message = conversationService.sendMessage(id,
                    content == null ? null : String.valueOf(content));
            return ResponseEntity.ok(messageView(message));
        } catch (SkillApiException ex) {
            return errorFor(ex);
        }
    }

    /**
     * Stepwise resume: take the conversation's waiting_input task → advance
     * (back to running + note merged into inputs) → record the advanced event
     * (skill_task_event + conversation projection) → job#resumeTask continues
     * from the pause point.
     * Optional body: {@code {"note": "追加要求"}}.
     */
    @PostMapping("/conversations/{id}/advance")
    public ResponseEntity<Object> advance(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        try {
            SkillOptimizeTask waiting = optimizeService.findWaitingInputTask(id);
            if (waiting == null) {
                Map<String, Object> notReady = new LinkedHashMap<String, Object>();
                notReady.put("message", "当前会话没有等待推进的分步优化任务");
                notReady.put("code", "NO_WAITING_TASK");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(notReady);
            }
            Object noteValue = body == null ? null : body.get("note");
            String note = noteValue == null ? null : String.valueOf(noteValue);
            SkillOptimizeTask advanced = optimizeService.advance(waiting.getId(), note);
            recordAdvancedEvent(advanced, note);
            optimizeTaskJob.resumeTask(advanced.getId());
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("id", advanced.getId());
            view.put("conversationId", advanced.getConversationId());
            view.put("status", advanced.getStatus());
            view.put("progressStage", advanced.getProgressStage());
            view.put("progressPercent", advanced.getProgressPercent());
            view.put("progressMessage", advanced.getProgressMessage());
            view.put("updatedAt", advanced.getUpdatedAt() == null ? null : advanced.getUpdatedAt().toString());
            return ResponseEntity.ok(view);
        } catch (SkillApiException ex) {
            return errorFor(ex);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorBody(ex.getMessage(), "VALIDATION_ERROR"));
        }
    }

    // ------------------------------------------------------------- views

    /** ConversationSummary: id / slug / versionId / title / createdAt / updatedAt. */
    private Map<String, Object> summaryView(SkillConversation conversation) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        if (conversation == null) {
            return view;
        }
        view.put("id", conversation.getId());
        view.put("slug", conversation.getSlug());
        view.put("versionId", conversation.getVersionId());
        view.put("title", conversation.getTitle());
        view.put("createdAt", conversation.getCreatedAt() == null ? null : conversation.getCreatedAt().toString());
        view.put("updatedAt", conversation.getUpdatedAt() == null ? null : conversation.getUpdatedAt().toString());
        return view;
    }

    /** WorkbenchMessage: id / conversationId / seq / role / msgType / content / createdAt (+related ids). */
    private Map<String, Object> messageView(SkillMessage message) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        if (message == null) {
            return view;
        }
        view.put("id", message.getId());
        view.put("conversationId", message.getConversationId());
        view.put("seq", message.getSeq());
        view.put("role", message.getRole());
        view.put("msgType", message.getMsgType());
        view.put("content", message.getContent());
        view.put("relatedTaskId", message.getRelatedTaskId());
        view.put("relatedCandidateId", message.getRelatedCandidateId());
        view.put("createdAt", message.getCreatedAt() == null ? null : message.getCreatedAt().toString());
        return view;
    }

    // ------------------------------------------------------------- helpers

    /**
     * Record the advance step as a durable task event; with a conversation
     * binding it is also projected to skill_message and pushed over SSE.
     * Event persistence failure never blocks the advance itself.
     */
    private void recordAdvancedEvent(SkillOptimizeTask task, String note) {
        if (taskEventService == null || task == null || task.getId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("conversationId", task.getConversationId());
        payload.put("status", task.getStatus());
        if (note != null && !note.trim().isEmpty()) {
            payload.put("note", note.trim());
        }
        try {
            taskEventService.recordEvent(task.getId(), "advanced", SkillTaskEvent.LEVEL_INFO, payload);
        } catch (RuntimeException ex) {
            // Event recording must never block the advance flow.
        }
    }

    private ResponseEntity<Object> errorFor(SkillApiException ex) {
        boolean notFound = SkillApiException.CODE_SKILL_NOT_FOUND.equals(ex.getCode())
                || SkillApiException.CODE_VERSION_NOT_FOUND.equals(ex.getCode())
                || SkillConversationServiceImpl.CODE_CONVERSATION_NOT_FOUND.equals(ex.getCode());
        HttpStatus status = notFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(errorBody(ex.getMessage(), ex.getCode()));
    }

    private Map<String, Object> errorBody(String message, String code) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", message == null ? "" : message);
        body.put("code", code == null ? SkillApiException.CODE_VALIDATION_ERROR : code);
        return body;
    }
}
