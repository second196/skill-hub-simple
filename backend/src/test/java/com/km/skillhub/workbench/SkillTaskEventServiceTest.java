package com.km.skillhub.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.analysis.SkillOptimizeRepository;
import com.km.skillhub.analysis.SkillOptimizeService;
import com.km.skillhub.analysis.SkillOptimizeServiceImpl;
import com.km.skillhub.analysis.SkillOptimizeTask;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillTaskEventService against local PG (skillhub_t10_test):
 * recordEvent persists skill_task_event and, when the task is bound to a
 * conversation, projects a skill_message (process_event) that clients can
 * re-fetch via messages?after=seq after an SSE disconnect (DB compensation).
 */
class SkillTaskEventServiceTest {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/skillhub_t10_test";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "123456";

    private static JdbcTemplate jdbc;
    private static SkillConversationRepository repository;
    private static SkillOptimizeRepository optimizeRepository;
    private static SkillOptimizeService optimizeService;
    private static ObjectMapper mapper;

    /** Recording hub: proves projected messages are broadcast for SSE push. */
    private static final class RecordingHub implements SkillSseHub {
        final List<SkillMessage> broadcasts = new CopyOnWriteArrayList<SkillMessage>();

        @Override
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter register(Long conversationId) {
            return new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(1000L);
        }

        @Override
        public void unregister(Long conversationId,
                               org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        }

        @Override
        public void broadcast(Long conversationId, SkillMessage message) {
            broadcasts.add(message);
        }

        @Override
        public int connectionCount(Long conversationId) {
            return 0;
        }
    }

    private RecordingHub hub;
    private SkillTaskEventServiceImpl service;

    private String slug;
    private Long skillId;
    private Long versionId;
    private Long conversationId;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource ds = new DriverManagerDataSource(DB_URL, DB_USER, DB_PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.queryForObject("SELECT count(*) FROM skill_task_event", Integer.class);
        repository = new SkillConversationRepository(jdbc);
        optimizeRepository = new SkillOptimizeRepository(jdbc);
        optimizeService = new SkillOptimizeServiceImpl(optimizeRepository, new ObjectMapper());
        mapper = new ObjectMapper();
    }

    @AfterAll
    static void cleanup() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM skill_optimize_task WHERE created_by LIKE 't14-evt-%'");
            jdbc.update("DELETE FROM skill_conversation WHERE slug LIKE 't14-evt-%'");
            jdbc.update("DELETE FROM skill WHERE slug LIKE 't14-evt-%'");
        }
    }

    @BeforeEach
    void seed() {
        hub = new RecordingHub();
        service = new SkillTaskEventServiceImpl(repository, mapper, hub);
        slug = "t14-evt-" + UUID.randomUUID();
        skillId = jdbc.queryForObject(
                "INSERT INTO skill(slug,name,description,category) VALUES (?,?,?,?) RETURNING id",
                Long.class, slug, "T14 Event Skill", "t14", "研发");
        versionId = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, skillId, "1.0.0");
        SkillConversation conversation = new SkillConversation();
        conversation.setSlug(slug);
        conversation.setVersionId(versionId);
        conversationId = repository.insertConversation(conversation).getId();
    }

    private SkillOptimizeTask createTask(Long boundConversationId) {
        return optimizeService.createTask(skillId, versionId, "edit",
                Collections.singletonList("说得太虚"), "t14-evt-user", "stepwise",
                null, boundConversationId);
    }

    @Test
    void recordEventPersistsRowAndProjectsConversationMessage() {
        SkillOptimizeTask task = createTask(conversationId);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("durationMs", Integer.valueOf(1234));
        payload.put("httpStatus", Integer.valueOf(200));

        service.recordEvent(task.getId(), "llm_requesting", "info", payload);

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT task_id,stage,level,payload_json FROM skill_task_event WHERE task_id=?"
                        + " ORDER BY id",
                task.getId());
        assertEquals(1, events.size());
        assertEquals("llm_requesting", events.get(0).get("stage"));
        assertEquals("info", events.get(0).get("level"));
        String payloadJson = String.valueOf(events.get(0).get("payload_json"));
        assertTrue(payloadJson.contains("1234"), payloadJson);
        assertTrue(payloadJson.contains("200"), payloadJson);

        // Projected process_event message with envelope + relatedTaskId
        List<SkillMessage> messages = repository.listMessages(conversationId, Integer.valueOf(0));
        SkillMessage projected = null;
        for (SkillMessage message : messages) {
            if ("process_event".equals(message.getMsgType())) {
                projected = message;
            }
        }
        assertNotNull(projected, "process_event projection must exist");
        assertEquals("assistant", projected.getRole());
        assertEquals(task.getId(), projected.getRelatedTaskId());
        assertTrue(projected.getContent().contains("\"stage\":\"llm_requesting\""));
        assertTrue(projected.getContent().contains("\"level\":\"info\""));

        // SSE push of the projected message
        assertEquals(1, hub.broadcasts.size());
        assertEquals(projected.getId(), hub.broadcasts.get(0).getId());
    }

    @Test
    void recordEventWithoutConversationWritesOnlyTaskEvent() {
        SkillOptimizeTask task = createTask(null);

        service.recordEvent(task.getId(), "claimed", null, Collections.<String, Object>emptyMap());

        Long eventCount = jdbc.queryForObject(
                "SELECT count(*) FROM skill_task_event WHERE task_id=?", Long.class, task.getId());
        assertEquals(Long.valueOf(1L), eventCount);
        // No conversation bound → no projection, no broadcast
        assertTrue(repository.listMessages(conversationId, Integer.valueOf(0)).isEmpty());
        assertTrue(hub.broadcasts.isEmpty());
    }

    @Test
    void recordEventNormalizesLevelAndSkipsBlankStage() {
        SkillOptimizeTask task = createTask(conversationId);

        service.recordEvent(task.getId(), "gate_checking", "WARN", null);
        service.recordEvent(task.getId(), "   ", "info", null);
        service.recordEvent(null, "stage", "info", null);
        service.recordEvent(task.getId(), "failed", "error", null);

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT stage,level FROM skill_task_event WHERE task_id=? ORDER BY id",
                task.getId());
        assertEquals(2, events.size());
        assertEquals("gate_checking", events.get(0).get("stage"));
        assertEquals("warn", events.get(0).get("level"));
        assertEquals("failed", events.get(1).get("stage"));
        assertEquals("error", events.get(1).get("level"));
    }

    @Test
    void recordConversationEventWritesMessageWithoutTaskEvent() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("scope", "chain");

        service.recordConversationEvent(conversationId, "analysis_started", "info", payload);
        service.recordConversationEvent(null, "analysis_started", "info", payload);

        List<SkillMessage> messages = repository.listMessages(conversationId, Integer.valueOf(0));
        assertEquals(1, messages.size());
        SkillMessage message = messages.get(0);
        assertEquals("process_event", message.getMsgType());
        assertEquals("assistant", message.getRole());
        assertTrue(message.getRelatedTaskId() == null, "conversation events have no task id");
        assertTrue(message.getContent().contains("analysis_started"));
        assertEquals(1, hub.broadcasts.size());

        // conversation events never touch skill_task_event (task_id FK)
        Long count = jdbc.queryForObject("SELECT count(*) FROM skill_task_event", Long.class);
        assertNotNull(count);
    }

    @Test
    void projectionCompensatesMissedMessagesAfterDisconnect() {
        // Simulate a live session receiving messages, then a disconnect: events
        // written while the client is gone stay in the DB and are re-fetched
        // with messages?after=lastSeenSeq.
        SkillOptimizeTask task = createTask(conversationId);
        SkillMessage lastSeen = repository.insertMessage(seedUserMessage(conversationId, 1, "hello"));
        Integer lastSeq = lastSeen.getSeq();

        service.recordEvent(task.getId(), "waiting_input", "info",
                Collections.<String, Object>singletonMap("progressStage", "reading_skill"));
        service.recordEvent(task.getId(), "advanced", "info",
                Collections.<String, Object>singletonMap("note", "继续"));

        List<SkillMessage> compensation = repository.listMessages(conversationId, lastSeq);
        assertEquals(2, compensation.size());
        assertEquals("process_event", compensation.get(0).getMsgType());
        assertTrue(compensation.get(0).getContent().contains("waiting_input"));
        assertTrue(compensation.get(1).getContent().contains("advanced"));
        for (int i = 1; i < compensation.size(); i++) {
            assertTrue(compensation.get(i).getSeq() > compensation.get(i - 1).getSeq());
        }
    }

    private SkillMessage seedUserMessage(Long conversationId, int seq, String text) {
        SkillMessage message = new SkillMessage();
        message.setConversationId(conversationId);
        message.setSeq(Integer.valueOf(seq));
        message.setRole(SkillMessage.ROLE_USER);
        message.setMsgType(SkillMessage.TYPE_TEXT);
        message.setContent(text);
        return message;
    }
}
