package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.skill.SkillApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cancel / decision state machine against local PG (skillhub_t10_test).
 * Fixtures use unique slugs so concurrent runs never collide.
 */
class SkillOptimizeServiceCancelDecisionTest {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/skillhub_t10_test";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "123456";

    private static JdbcTemplate jdbc;
    private static SkillOptimizeRepository repository;
    private static SkillOptimizeServiceImpl service;

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
        service = new SkillOptimizeServiceImpl(repository, new ObjectMapper());
    }

    @AfterAll
    static void cleanup() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM skill_optimize_task WHERE created_by LIKE 't13-cancel-%'");
            jdbc.update("DELETE FROM skill WHERE slug LIKE 't13-cancel-%'");
        }
    }

    @BeforeEach
    void seedSkill() {
        slug = "t13-cancel-" + UUID.randomUUID();
        skillId = jdbc.queryForObject(
                "INSERT INTO skill(slug,name,description,category) VALUES (?,?,?,?) RETURNING id",
                Long.class, slug, "T13 Cancel Skill", "t13", "研发");
        versionId = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, skillId, "1.0.0");
        jdbc.update(
                "INSERT INTO skill_file(version_id,path,content,content_type,size_bytes,content_digest)"
                        + " VALUES (?,?,?,?,?,?)",
                versionId, "SKILL.md", ("# " + slug + "\ncontent\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/markdown", Integer.valueOf(20), digest(slug + "-f1"));
    }

    @Test
    void cancelMovesQueuedToRejectedWithExplainableReason() {
        SkillOptimizeTask task = service.createTask(skillId, versionId, "edit",
                Collections.singletonList("说得太虚"), "t13-cancel-user", "auto", null, null);
        assertEquals("queued", task.getStatus());

        SkillOptimizeTask cancelled = service.cancelTask(task.getId(), "alice", "改主意了");
        assertEquals("rejected", cancelled.getStatus());
        assertTrue(cancelled.getFailReason().contains("用户取消"));
        assertTrue(cancelled.getFailReason().contains("改主意了"));
        assertEquals(Integer.valueOf(100), cancelled.getProgressPercent());
    }

    @Test
    void cancelRejectsTerminalStatus() {
        SkillOptimizeTask task = service.createTask(skillId, versionId, "repair",
                null, "t13-cancel-user", "auto", null, null);
        service.cancelTask(task.getId(), null, null);
        SkillApiException ex = assertThrows(SkillApiException.class,
                () -> service.cancelTask(task.getId(), null, null));
        assertEquals(SkillOptimizeServiceImpl.CODE_OPTIMIZE_CONFLICT, ex.getCode());
    }

    @Test
    void decideAdoptCreatesVersionAndMovesTaskToAdopted() {
        SkillOptimizeTask task = service.createTask(skillId, versionId, "edit",
                null, "t13-cancel-user", "auto", null, null);
        service.markRunning(task.getId());
        SkillOptimizeCandidate candidate = service.addCandidate(task.getId(),
                "补充示例章节", buildDiff("SKILL.md"), Collections.emptyList());
        // force pending_review by evaluating with passing gates
        service.evaluateCandidate(candidate.getId(),
                new SkillOptimizeGate.Metrics(0.5d, 0.2d, 70d),
                new SkillOptimizeGate.Metrics(0.56d, 0.18d, 72d),
                SkillOptimizeGate.Evaluation.testsPassed(1, 1,
                        java.util.Arrays.asList("better", "better", "tie")));
        SkillOptimizeTask reviewed = service.getTask(task.getId());
        assertEquals("pending_review", reviewed.getStatus());
        assertNotNull(candidate.getId());

        SkillOptimizeCandidate decided = service.decide(candidate.getId(), "adopt", "bob", "看起来不错");
        assertEquals("adopted", decided.getDecision());
        assertNotNull(decided.getResultVersionId());
        assertNotNull(decided.getResultVersionLabel());
        assertEquals("adopted", service.getTask(task.getId()).getStatus());
        assertTrue(jdbc.queryForObject(
                "SELECT count(*) FROM skill_file WHERE version_id=?", Integer.class,
                decided.getResultVersionId()).intValue() > 0);
    }

    @Test
    void decideRejectRecordsReasonAndRejectsTask() {
        SkillOptimizeTask task = service.createTask(skillId, versionId, "edit",
                null, "t13-cancel-user", "auto", null, null);
        service.markRunning(task.getId());
        SkillOptimizeCandidate candidate = service.addCandidate(task.getId(),
                "候选 A", buildDiff("SKILL.md"), Collections.emptyList());
        service.evaluateCandidate(candidate.getId(),
                new SkillOptimizeGate.Metrics(0.5d, 0.2d, 70d),
                new SkillOptimizeGate.Metrics(0.56d, 0.18d, 72d),
                SkillOptimizeGate.Evaluation.testsPassed(1, 1,
                        java.util.Arrays.asList("better", "better", "tie")));

        SkillOptimizeCandidate rejected = service.decide(candidate.getId(), "reject", "carol", "方向不对");
        assertEquals("rejected", rejected.getDecision());
        assertEquals("方向不对", rejected.getDecisionReason());
        assertEquals("rejected", service.getTask(task.getId()).getStatus());
    }

    @Test
    void decideRejectsWrongTaskStatus() {
        SkillOptimizeTask task = service.createTask(skillId, versionId, "edit",
                null, "t13-cancel-user", "auto", null, null);
        service.markRunning(task.getId());
        SkillOptimizeCandidate candidate = service.addCandidate(task.getId(),
                "未过门禁的候选", buildDiff("SKILL.md"), Collections.emptyList());
        // leave task in running / gated_fail path: evaluate fails gates (no metrics gain)
        service.evaluateCandidate(candidate.getId(),
                new SkillOptimizeGate.Metrics(0.5d, null, null),
                new SkillOptimizeGate.Metrics(0.5d, null, null),
                SkillOptimizeGate.Evaluation.testsPassed(1, 1,
                        Collections.singletonList("worse")));
        assertEquals("gated_fail", service.getTask(task.getId()).getStatus());

        SkillApiException ex = assertThrows(SkillApiException.class,
                () -> service.decide(candidate.getId(), "adopt", "dave", null));
        assertEquals(SkillOptimizeServiceImpl.CODE_OPTIMIZE_CONFLICT, ex.getCode());
    }

    @Test
    void waitingInputCanAdvanceBackToRunning() {
        SkillOptimizeTask task = service.createTask(skillId, versionId, "edit",
                null, "t13-cancel-user", "stepwise", null, null);
        service.markRunning(task.getId());
        // simulate job pause
        task.setStatus(SkillOptimizeTask.STATUS_WAITING_INPUT);
        repository.updateTask(task);

        SkillOptimizeTask advanced = service.advance(task.getId(), "继续");
        assertEquals("running", advanced.getStatus());
        assertTrue(advanced.getInputsJson().contains("继续"));
    }

    @Test
    void duplicateActiveTaskIsRejected() {
        service.createTask(skillId, versionId, "edit", null, "t13-cancel-user", "auto", null, null);
        SkillApiException ex = assertThrows(SkillApiException.class,
                () -> service.createTask(skillId, versionId, "edit", null, "t13-cancel-user",
                        "auto", null, null));
        assertEquals(SkillOptimizeServiceImpl.CODE_OPTIMIZE_CONFLICT, ex.getCode());
    }

    private static String buildDiff(String path) {
        return "--- a/" + path + "\n+++ b/" + path + "\n@@ -1,2 +1,3 @@\n # Skill\n+补充示例章节\n content\n";
    }

    private static String digest(String seed) {
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
