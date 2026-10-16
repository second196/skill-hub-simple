package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Job LLM-unavailable path and React step writing (skillhub_t10_test).
 * When AnalysisModelClient is disabled the task must fail explainably, not throw.
 */
class SkillOptimizeTaskJobTest {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/skillhub_t10_test";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "123456";

    private static JdbcTemplate jdbc;
    private static SkillOptimizeRepository repository;
    private static SkillOptimizeServiceImpl service;
    private static ObjectMapper mapper;

    private String slug;
    private Long skillId;
    private Long versionId;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource ds = new DriverManagerDataSource(DB_URL, DB_USER, DB_PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.queryForObject("SELECT count(*) FROM skill_optimize_task", Integer.class);
        repository = new SkillOptimizeRepository(jdbc);
        mapper = new ObjectMapper();
        service = new SkillOptimizeServiceImpl(repository, mapper);
    }

    @AfterAll
    static void cleanup() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM skill_optimize_task WHERE created_by LIKE 't13-job-%'");
            jdbc.update("DELETE FROM skill WHERE slug LIKE 't13-job-%'");
        }
    }

    @BeforeEach
    void seedSkill() {
        slug = "t13-job-" + UUID.randomUUID();
        skillId = jdbc.queryForObject(
                "INSERT INTO skill(slug,name,description,category) VALUES (?,?,?,?) RETURNING id",
                Long.class, slug, "T13 Job Skill", "t13", "研发");
        versionId = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, skillId, "1.0.0");
        jdbc.update(
                "INSERT INTO skill_file(version_id,path,content,content_type,size_bytes,content_digest)"
                        + " VALUES (?,?,?,?,?,?)",
                versionId, "SKILL.md",
                ("---\nname: x\n---\n# Skill\nbody\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/markdown", Integer.valueOf(30), sha(slug + "-f1"));
    }

    @Test
    void llmDisabledFailsTaskExplainablyAndWritesReactSteps() {
        SkillOptimizeTask task = service.createTask(skillId, versionId, "edit",
                Collections.singletonList("说得太虚"), "t13-job-user", "auto", null, null);
        // AnalysisModelClient disabled by default (yml enabled=false)
        SkillOptimizeTaskJob job = new SkillOptimizeTaskJob(repository, service,
                disabledClient(), mapper, 0, false);
        service.markRunning(task.getId());

        job.executeTask(repository.findTask(task.getId()));

        SkillOptimizeTask finished = service.getTask(task.getId());
        assertEquals("gated_fail", finished.getStatus());
        assertNotNull(finished.getFailReason());
        assertTrue(finished.getFailReason().contains("AI")
                || finished.getFailReason().contains("模型"));
        assertEquals("failed", finished.getProgressStage());
        assertEquals(Integer.valueOf(100), finished.getProgressPercent());

        List<Map<String, Object>> steps = jdbc.queryForList(
                "SELECT step_no,action_type FROM skill_react_step WHERE task_id=? ORDER BY step_no",
                task.getId());
        assertFalse(steps.isEmpty(), "react steps must be written even on LLM failure");
        assertEquals("read_skill", steps.get(0).get("action_type"));
    }

    @Test
    void stepwiseRunModePausesAtWaitingInputWithoutCallingLlm() {
        SkillOptimizeTask task = service.createTask(skillId, versionId, "edit",
                null, "t13-job-user", "stepwise", null, null);
        SkillOptimizeTaskJob job = new SkillOptimizeTaskJob(repository, service,
                disabledClient(), mapper, 0, false);
        service.markRunning(task.getId());

        job.executeTask(repository.findTask(task.getId()));

        SkillOptimizeTask paused = service.getTask(task.getId());
        assertEquals("waiting_input", paused.getStatus());
        assertTrue(paused.getProgressMessage().contains("确认")
                || paused.getProgressMessage().contains("等待"));
    }

    @Test
    void processOnceClaimsQueuedTaskAndCompletesWithoutUncaughtException() {
        SkillOptimizeTask task = service.createTask(skillId, versionId, "repair",
                null, "t13-job-user", "auto", null, null);
        // findQueuedTasks is global ORDER BY id ASC; demote foreign queued leftovers
        // so this test always claims its own task under the shared test DB.
        jdbc.update("UPDATE skill_optimize_task SET status='rejected',"
                + " fail_reason='t30 test isolation' WHERE status='queued' AND id<>?",
                task.getId());
        SkillOptimizeTaskJob job = new SkillOptimizeTaskJob(repository, service,
                disabledClient(), mapper, 0, false);
        job.processOnce();
        SkillOptimizeTask finished = service.getTask(task.getId());
        assertEquals("gated_fail", finished.getStatus());
        assertTrue(finished.getFailReason() != null && finished.getFailReason().length() > 0);
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
