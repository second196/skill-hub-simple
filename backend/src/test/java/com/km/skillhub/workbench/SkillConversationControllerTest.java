package com.km.skillhub.workbench;

import com.km.skillhub.analysis.SkillOptimizeCandidate;
import com.km.skillhub.analysis.SkillOptimizeGate;
import com.km.skillhub.analysis.SkillOptimizeService;
import com.km.skillhub.analysis.SkillOptimizeTask;
import com.km.skillhub.analysis.SkillOptimizeTaskJob;
import com.km.skillhub.skill.SkillApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillConversationController contract: view shapes (ConversationSummary /
 * WorkbenchMessage), SSE registration, error bodies {message, code} with
 * 404/400 mapping, and the advance orchestration (find waiting task →
 * advance → record event → job resume → view).
 */
class SkillConversationControllerTest {

    /** Conversation service stub recording calls and returning fixtures. */
    private static final class StubConversationService implements SkillConversationService {
        SkillConversation lastCreated;
        String lastSlug;
        Long lastVersionId;
        String lastContent;
        Long lastListId;
        Integer lastAfter;

        @Override
        public SkillConversation createConversation(String slug, Long versionId) {
            if ("missing".equals(slug)) {
                throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
            }
            if ("version-missing".equals(slug)) {
                throw new SkillApiException("Skill版本不存在", SkillApiException.CODE_VERSION_NOT_FOUND);
            }
            if ("bad".equals(slug)) {
                throw new SkillApiException("slug 不能为空", SkillApiException.CODE_VALIDATION_ERROR);
            }
            lastSlug = slug;
            lastVersionId = versionId;
            SkillConversation conversation = new SkillConversation();
            conversation.setId(Long.valueOf(1L));
            conversation.setSlug(slug);
            conversation.setVersionId(versionId);
            conversation.setTitle(null);
            conversation.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
            conversation.setUpdatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
            lastCreated = conversation;
            return conversation;
        }

        @Override
        public List<SkillConversation> listConversations(String slug, Long versionId) {
            if ("missing".equals(slug)) {
                throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
            }
            List<SkillConversation> rows = new ArrayList<SkillConversation>();
            SkillConversation conversation = new SkillConversation();
            conversation.setId(Long.valueOf(2L));
            conversation.setSlug(slug);
            conversation.setVersionId(versionId);
            conversation.setTitle("第一条输入摘要");
            conversation.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
            conversation.setUpdatedAt(OffsetDateTime.parse("2026-01-02T00:00:00Z"));
            rows.add(conversation);
            return rows;
        }

        @Override
        public List<SkillMessage> listMessages(Long conversationId, Integer after) {
            lastListId = conversationId;
            lastAfter = after;
            List<SkillMessage> rows = new ArrayList<SkillMessage>();
            SkillMessage message = new SkillMessage();
            message.setId(Long.valueOf(9L));
            message.setConversationId(conversationId);
            message.setSeq(Integer.valueOf(3));
            message.setRole("user");
            message.setMsgType("text");
            message.setContent("你好");
            message.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
            rows.add(message);
            return rows;
        }

        @Override
        public SkillMessage sendMessage(Long conversationId, String content) {
            if (conversationId != null && conversationId.longValue() == 404L) {
                throw new SkillApiException("会话不存在",
                        SkillConversationServiceImpl.CODE_CONVERSATION_NOT_FOUND);
            }
            lastContent = content;
            SkillMessage message = new SkillMessage();
            message.setId(Long.valueOf(10L));
            message.setConversationId(conversationId);
            message.setSeq(Integer.valueOf(4));
            message.setRole("user");
            message.setMsgType("text");
            message.setContent(content);
            message.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
            return message;
        }
    }

    /** SSE hub stub: hands out a fresh emitter and counts registrations. */
    private static final class StubHub implements SkillSseHub {
        int registers;
        Long lastConversationId;

        @Override
        public SseEmitter register(Long conversationId) {
            registers++;
            lastConversationId = conversationId;
            return new SseEmitter(60_000L);
        }

        @Override
        public void unregister(Long conversationId, SseEmitter emitter) {
        }

        @Override
        public void broadcast(Long conversationId, SkillMessage message) {
        }

        @Override
        public int connectionCount(Long conversationId) {
            return registers;
        }
    }

    /** Task event stub: records the last advanced event. */
    private static final class StubTaskEventService implements SkillTaskEventService {
        Long lastTaskId;
        String lastStage;

        @Override
        public void recordEvent(Long taskId, String stage, String level, Map<String, Object> payload) {
            lastTaskId = taskId;
            lastStage = stage;
        }

        @Override
        public void recordConversationEvent(Long conversationId, String stage, String level,
                                            Map<String, Object> payload) {
        }
    }

    /**
     * Optimize service stub: findWaitingInputTask/advance with a simple state
     * machine so the controller orchestration can be asserted without a DB.
     */
    private static final class StubOptimizeService implements SkillOptimizeService {
        SkillOptimizeTask waiting;
        Long lastAdvancedId;
        String lastNote;

        SkillOptimizeTask taskWith(Long id, String status) {
            SkillOptimizeTask task = new SkillOptimizeTask();
            task.setId(id);
            task.setConversationId(Long.valueOf(100L));
            task.setStatus(status);
            task.setProgressStage("reading_skill");
            task.setProgressPercent(Integer.valueOf(20));
            task.setProgressMessage("已读取文件，等待确认继续生成候选");
            task.setUpdatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
            return task;
        }

        @Override
        public SkillOptimizeTask findWaitingInputTask(Long conversationId) {
            return waiting;
        }

        @Override
        public SkillOptimizeTask advance(Long taskId, String note) {
            lastAdvancedId = taskId;
            lastNote = note;
            if (waiting == null || !waiting.getId().equals(taskId)) {
                throw new SkillApiException("当前状态不允许推进（仅 waiting_input 可推进）",
                        "OPTIMIZE_TASK_CONFLICT");
            }
            SkillOptimizeTask advanced = taskWith(taskId, "running");
            advanced.setProgressMessage("继续生成候选");
            return advanced;
        }

        @Override
        public SkillOptimizeTask createTask(Long skillId, Long versionId, String mode,
                                            List<String> priorities, String createdBy,
                                            String runMode, List<String> targetIssueIds,
                                            Long conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillOptimizeTask cancelTask(Long taskId, String operator, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillOptimizeTask markRunning(Long taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillOptimizeTask failTask(Long taskId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillOptimizeTask updateProgress(Long taskId, String stage, int percent, String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillOptimizeTask getTask(Long taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SkillOptimizeTask> listTasks(Long skillId, String mode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillOptimizeCandidate addCandidate(Long taskId, String summary, String diffText,
                                                   List<SkillOptimizeGate.Edit> edits) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillOptimizeCandidate evaluateCandidate(Long candidateId,
                                                        SkillOptimizeGate.Metrics baseline,
                                                        SkillOptimizeGate.Metrics candidate,
                                                        SkillOptimizeGate.Evaluation evidence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillOptimizeCandidate evaluateCandidate(Long candidateId,
                                                        SkillOptimizeGate.Metrics baseline,
                                                        SkillOptimizeGate.Metrics candidate,
                                                        SkillOptimizeGate.Evaluation evidence,
                                                        Map<String, String> evidenceTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillOptimizeCandidate decide(Long candidateId, String decision, String operator, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillOptimizeCandidate getCandidate(Long candidateId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SkillOptimizeCandidate> listCandidates(Long taskId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Job stub: records resumeTask calls instead of running the pipeline. */
    private static final class StubOptimizeJob extends SkillOptimizeTaskJob {
        final List<Long> resumed = new ArrayList<Long>();

        StubOptimizeJob() {
            super(null, null, null, new com.fasterxml.jackson.databind.ObjectMapper(), 0, false);
        }

        @Override
        public void resumeTask(Long taskId) {
            resumed.add(taskId);
        }
    }

    private final StubConversationService conversationService = new StubConversationService();
    private final StubHub hub = new StubHub();
    private final StubTaskEventService taskEventService = new StubTaskEventService();
    private final StubOptimizeService optimizeService = new StubOptimizeService();
    private final StubOptimizeJob optimizeJob = new StubOptimizeJob();
    private final SkillConversationController controller = new SkillConversationController(
            conversationService, hub, taskEventService, optimizeService, optimizeJob);

    @Test
    void createReturnsConversationSummaryView() {
        ResponseEntity<Object> response = controller.create("demo", Long.valueOf(5L));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) response.getBody();
        assertEquals(Long.valueOf(1L), view.get("id"));
        assertEquals("demo", view.get("slug"));
        assertEquals(Long.valueOf(5L), view.get("versionId"));
        assertTrue(view.containsKey("title"));
        assertNotNull(view.get("createdAt"));
        assertNotNull(view.get("updatedAt"));
        assertEquals("demo", conversationService.lastSlug);
        assertEquals(Long.valueOf(5L), conversationService.lastVersionId);
    }

    @Test
    void createMapsErrorsTo404And400WithMessageCodeBody() {
        ResponseEntity<Object> missing = controller.create("missing", Long.valueOf(1L));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) missing.getBody();
        assertEquals("SKILL_NOT_FOUND", body.get("code"));

        ResponseEntity<Object> versionMissing = controller.create("version-missing", Long.valueOf(1L));
        assertEquals(HttpStatus.NOT_FOUND, versionMissing.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> versionBody = (Map<String, Object>) versionMissing.getBody();
        assertEquals("VERSION_NOT_FOUND", versionBody.get("code"));

        ResponseEntity<Object> bad = controller.create("bad", Long.valueOf(1L));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> badBody = (Map<String, Object>) bad.getBody();
        assertEquals("VALIDATION_ERROR", badBody.get("code"));
    }

    @Test
    void listReturnsSummaryArrayWithCursorContract() {
        ResponseEntity<Object> response = controller.list("demo", Long.valueOf(3L));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof List);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody();
        assertEquals(1, items.size());
        assertEquals("第一条输入摘要", items.get(0).get("title"));
    }

    @Test
    void messagesReturnsWorkbenchMessageViewAndPassesAfterCursor() {
        ResponseEntity<Object> response = controller.messages(Long.valueOf(7L), Integer.valueOf(2));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody();
        assertEquals(1, items.size());
        Map<String, Object> view = items.get(0);
        assertEquals(Long.valueOf(9L), view.get("id"));
        assertEquals(Long.valueOf(7L), view.get("conversationId"));
        assertEquals(Integer.valueOf(3), view.get("seq"));
        assertEquals("user", view.get("role"));
        assertEquals("text", view.get("msgType"));
        assertEquals("你好", view.get("content"));
        assertTrue(view.containsKey("relatedTaskId"));
        assertNotNull(view.get("createdAt"));

        assertEquals(Long.valueOf(7L), conversationService.lastListId);
        assertEquals(Integer.valueOf(2), conversationService.lastAfter);
    }

    @Test
    void sendReturnsUserMessageViewAndMapsMissingConversationTo404() {
        ResponseEntity<Object> ok = controller.send(Long.valueOf(7L),
                new LinkedHashMap<String, Object>() {{ put("content", "帮我分析"); }});
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) ok.getBody();
        assertEquals("user", view.get("role"));
        assertEquals("帮我分析", view.get("content"));
        assertEquals("帮我分析", conversationService.lastContent);

        ResponseEntity<Object> missing = controller.send(Long.valueOf(404L),
                new LinkedHashMap<String, Object>() {{ put("content", "x"); }});
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) missing.getBody();
        assertEquals("CONVERSATION_NOT_FOUND", body.get("code"));
    }

    @Test
    void eventsRegistersEmitterOnHub() {
        SseEmitter emitter = controller.events(Long.valueOf(42L));
        assertNotNull(emitter);
        assertEquals(1, hub.registers);
        assertEquals(Long.valueOf(42L), hub.lastConversationId);
    }

    @Test
    void advanceWithoutWaitingTaskReturnsNoWaitingTask400() {
        optimizeService.waiting = null;
        ResponseEntity<Object> response = controller.advance(Long.valueOf(1L), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("NO_WAITING_TASK", body.get("code"));
        assertTrue(optimizeJob.resumed.isEmpty(), "no resume without a waiting task");
    }

    @Test
    void advanceFindsWaitsTaskAdvancesRecordsEventAndResumes() {
        optimizeService.waiting = optimizeService.taskWith(Long.valueOf(9L), "waiting_input");
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("note", "请保持简洁");

        ResponseEntity<Object> response = controller.advance(Long.valueOf(1L), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Long.valueOf(9L), optimizeService.lastAdvancedId);
        assertEquals("请保持简洁", optimizeService.lastNote);
        assertEquals(Long.valueOf(9L), taskEventService.lastTaskId);
        assertEquals("advanced", taskEventService.lastStage);
        assertEquals(1, optimizeJob.resumed.size());
        assertEquals(Long.valueOf(9L), optimizeJob.resumed.get(0));

        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) response.getBody();
        assertEquals(Long.valueOf(9L), view.get("id"));
        assertEquals(Long.valueOf(100L), view.get("conversationId"));
        assertEquals("running", view.get("status"));
        assertEquals("reading_skill", view.get("progressStage"));
        assertEquals(Integer.valueOf(20), view.get("progressPercent"));
        assertNotNull(view.get("progressMessage"));
        assertNotNull(view.get("updatedAt"));
    }
}
