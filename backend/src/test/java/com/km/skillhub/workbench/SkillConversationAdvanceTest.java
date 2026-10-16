package com.km.skillhub.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.analysis.AnalysisModelClient;
import com.km.skillhub.analysis.SkillOptimizeRepository;
import com.km.skillhub.analysis.SkillOptimizeServiceImpl;
import com.km.skillhub.analysis.SkillOptimizeTask;
import com.km.skillhub.analysis.SkillOptimizeTaskJob;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Advance state transition end-to-end against local PG (skillhub_t10_test):
 * stepwise run pauses at waiting_input → controller advance flips to running
 * with the note merged into inputs → job#resumeTask continues from the pause
 * point (LLM stage, NOT a re-pause) → with the model disabled the task ends
 * in an explainable gated_fail. Also covers NO_WAITING_TASK and the advanced
 * process-event projection used for SSE compensation.
 */
class SkillConversationAdvanceTest {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/skillhub_t10_test";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "123456";

    private static JdbcTemplate jdbc;
    private static SkillConversationRepository repository;
    private static SkillOptimizeRepository optimizeRepository;
    private static SkillOptimizeServiceImpl optimizeService;
    private static ObjectMapper mapper;

    private SkillConversationController controller;
    private String slug;
    private Long skillId;
    private Long versionId;
    private Long conversationId;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource ds = new DriverManagerDataSource(DB_URL, DB_USER, DB_PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.queryForObject("SELECT count(*) FROM skill_optimize_task", Integer.class);
        repository = new SkillConversationRepository(jdbc);
        optimizeRepository = new SkillOptimizeRepository(jdbc);
        optimizeService = new SkillOptimizeServiceImpl(optimizeRepository, new ObjectMapper());
        mapper = new ObjectMapper();
    }

    @AfterAll
    static void cleanup() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM skill_optimize_task WHERE created_by LIKE 't14-adv-%'");
            jdbc.update("DELETE FROM skill_conversation WHERE slug LIKE 't14-adv-%'");
            jdbc.update("DELETE FROM skill WHERE slug LIKE 't14-adv-%'");
        }
    }

    @BeforeEach
    void seed() {
        SkillSseHub noBroadcastHub = new SkillSseHub() {
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
            }

            @Override
            public int connectionCount(Long conversationId) {
                return 0;
            }
        };
        SkillConversationService conversationService = new SkillConversationServiceImpl(
                repository, mapper, noBroadcastHub);
        SkillTaskEventService taskEventService = new SkillTaskEventServiceImpl(
                repository, mapper, noBroadcastHub);
        // Test constructor: workers=0 + async=false → sync execution on caller thread
        SkillOptimizeTaskJob job = new SkillOptimizeTaskJob(optimizeRepository, optimizeService,
                disabledClient(), mapper, 0, false);
        controller = new SkillConversationController(conversationService, noBroadcastHub,
                taskEventService, optimizeService, job);

        slug = "t14-adv-" + UUID.randomUUID();
        skillId = jdbc.queryForObject(
                "INSERT INTO skill(slug,name,description,category) VALUES (?,?,?,?) RETURNING id",
                Long.class, slug, "T14 Adv Skill", "t14", "研发");
        versionId = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, skillId, "1.0.0");
        jdbc.update(
                "INSERT INTO skill_file(version_id,path,content,content_type,size_bytes,content_digest)"
                        + " VALUES (?,?,?,?,?,?)",
                versionId, "SKILL.md",
                ("---\nname: x\n---\n# Skill\nbody\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/markdown", Integer.valueOf(30), sha(slug + "-f1"));
        conversationId = conversationService.createConversation(slug, versionId).getId();
    }

    @Test
    void stepwisePauseThenAdvanceContinuesFromPausePoint() {
        // 1) stepwise task pauses at waiting_input after reading files
        SkillOptimizeTask task = optimizeService.createTask(skillId, versionId, "edit",
                Collections.singletonList("说得太虚"), "t14-adv-user", "stepwise",
                null, conversationId);
        optimizeService.markRunning(task.getId());
        SkillOptimizeTaskJob setupJob = new SkillOptimizeTaskJob(optimizeRepository, optimizeService,
                disabledClient(), mapper, 0, false);
        setupJob.executeTask(optimizeRepository.findTask(task.getId()));

        SkillOptimizeTask paused = optimizeService.getTask(task.getId());
        assertEquals("waiting_input", paused.getStatus());
        assertEquals("reading_skill", paused.getProgressStage());

        // 2) advance: waiting_input → running, note merged, advanced event projected.
        // The test job runs synchronously (workers=0, async=false), so by the
        // time advance() returns the resume has already continued to the LLM
        // stage and finished — the HTTP view is the in-flight "running"
        // snapshot, the DB row is the terminal state.
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("note", "请保持简洁");
        ResponseEntity<Object> response = controller.advance(conversationId, request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) response.getBody();
        assertEquals("running", view.get("status"));
        assertEquals(task.getId(), view.get("id"));
        assertEquals(conversationId, view.get("conversationId"));

        SkillOptimizeTask advanced = optimizeService.getTask(task.getId());
        assertTrue(advanced.getInputsJson().contains("请保持简洁"),
                "advance note must merge into inputsJson: " + advanced.getInputsJson());

        // advanced event: durable task event + conversation projection for SSE compensation
        Long eventCount = jdbc.queryForObject(
                "SELECT count(*) FROM skill_task_event WHERE task_id=? AND stage='advanced'",
                Long.class, task.getId());
        assertEquals(Long.valueOf(1L), eventCount);
        List<SkillMessage> projected = repository.listMessages(conversationId, Integer.valueOf(2));
        boolean sawAdvanced = false;
        for (SkillMessage message : projected) {
            if ("process_event".equals(message.getMsgType())
                    && message.getContent().contains("advanced")) {
                sawAdvanced = true;
            }
        }
        assertTrue(sawAdvanced, "advanced process_event must be projected to the conversation");

        // 3) resume continued from the LLM stage (did NOT re-pause at
        //    waiting_input); with the model disabled it ends in an explainable
        //    gated_fail.
        SkillOptimizeTask finished = optimizeService.getTask(task.getId());
        assertEquals("gated_fail", finished.getStatus());
        assertNotNull(finished.getFailReason());
        assertTrue(finished.getFailReason().contains("AI") || finished.getFailReason().contains("模型"),
                "resume must reach the LLM stage, failReason=" + finished.getFailReason());

        // resume marker step appended after the original read/pause steps
        Long resumeSteps = jdbc.queryForObject(
                "SELECT count(*) FROM skill_react_step WHERE task_id=? AND action_type='resume'",
                Long.class, task.getId());
        assertTrue(resumeSteps.longValue() >= 1L, "resume marker react step must be written");
    }

    @Test
    void advanceWithoutWaitingTaskReturnsNoWaitingTask() {
        ResponseEntity<Object> response = controller.advance(conversationId, null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("NO_WAITING_TASK", body.get("code"));
    }

    @Test
    void findWaitingInputTaskReturnsLatestBoundWaitingTask() {
        assertEquals(null, optimizeService.findWaitingInputTask(conversationId));

        SkillOptimizeTask task = optimizeService.createTask(skillId, versionId, "edit",
                null, "t14-adv-user", "stepwise", null, conversationId);
        optimizeService.markRunning(task.getId());
        SkillOptimizeTaskJob job = new SkillOptimizeTaskJob(optimizeRepository, optimizeService,
                disabledClient(), mapper, 0, false);
        job.executeTask(optimizeRepository.findTask(task.getId()));

        SkillOptimizeTask waiting = optimizeService.findWaitingInputTask(conversationId);
        assertNotNull(waiting);
        assertEquals(task.getId(), waiting.getId());
        assertEquals("waiting_input", waiting.getStatus());

        // Unbound conversation → null
        assertEquals(null, optimizeService.findWaitingInputTask(Long.valueOf(999999999L)));
        assertEquals(null, optimizeService.findWaitingInputTask(null));
    }

    @Test
    void resumeTaskRejectsMissingAndNonRunningTasks() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SkillOptimizeTaskJob(optimizeRepository, optimizeService,
                        disabledClient(), mapper, 0, false).resumeTask(Long.valueOf(999999999L)));

        SkillOptimizeTask task = optimizeService.createTask(skillId, versionId, "edit",
                null, "t14-adv-user", "auto", null, conversationId);
        optimizeService.markRunning(task.getId());
        SkillOptimizeTaskJob paused = new SkillOptimizeTaskJob(optimizeRepository, optimizeService,
                disabledClient(), mapper, 0, false);
        paused.executeTask(optimizeRepository.findTask(task.getId()));
        // auto mode ran to completion (gated_fail with model disabled) → not resumable
        SkillOptimizeTask finished = optimizeService.getTask(task.getId());
        org.junit.jupiter.api.Assertions.assertNotEquals("running", finished.getStatus());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new SkillOptimizeTaskJob(optimizeRepository, optimizeService,
                        disabledClient(), mapper, 0, false).resumeTask(task.getId()));
    }

    private static AnalysisModelClient disabledClient() {
        return new AnalysisModelClient(new ObjectMapper(), false, "", "", "gpt-4.1-mini",
                "text-embedding-3-small", 1000, 500, 1000L);
    }

    private static String sha(String seed) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
