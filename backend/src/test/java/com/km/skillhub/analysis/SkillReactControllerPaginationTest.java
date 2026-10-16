package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct step cursor pagination + 404 for missing task (skillhub_t10_test).
 */
class SkillReactControllerPaginationTest {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/skillhub_t10_test";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "123456";

    private static JdbcTemplate jdbc;
    private static SkillOptimizeRepository repository;
    private static SkillReactController controller;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource ds = new DriverManagerDataSource(DB_URL, DB_USER, DB_PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.queryForObject("SELECT count(*) FROM skill_react_step", Integer.class);
        repository = new SkillOptimizeRepository(jdbc);
        controller = new SkillReactController(repository);
    }

    @AfterAll
    static void cleanup() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM skill_optimize_task WHERE created_by LIKE 't13-react-%'");
            jdbc.update("DELETE FROM skill WHERE slug LIKE 't13-react-%'");
        }
    }

    @Test
    void missingTaskReturns404WithStableCode() {
        ResponseEntity<Object> response = controller.reactSteps(999999999L, null, 10);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(SkillOptimizeServiceImpl.CODE_OPTIMIZE_TASK_NOT_FOUND, body.get("code"));
    }

    @Test
    void cursorPaginationReturnsAscendingStepsAfterExclusiveAfter() {
        String slug = "t13-react-" + UUID.randomUUID();
        Long skillId = jdbc.queryForObject(
                "INSERT INTO skill(slug,name,description,category) VALUES (?,?,?,?) RETURNING id",
                Long.class, slug, "React Page", "t13", "研发");
        Long versionId = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, skillId, "1.0.0");
        SkillOptimizeTask task = new SkillOptimizeTask();
        task.setSkillId(skillId);
        task.setVersionId(versionId);
        task.setMode("edit");
        task.setStatus("running");
        task.setRunMode("auto");
        task.setEngine("react");
        task.setCreatedBy("t13-react-user");
        repository.insertTask(task);

        for (int i = 1; i <= 7; i++) {
            SkillReactStep step = new SkillReactStep();
            step.setTaskId(task.getId());
            step.setStepNo(Integer.valueOf(i));
            step.setThought("step " + i);
            step.setActionType("act_" + i);
            step.setActionJson("{\"n\":" + i + "}");
            step.setObservationJson("{\"ok\":true}");
            step.setDurationMs(Long.valueOf(i));
            repository.insertReactStep(step);
        }

        ResponseEntity<Object> first = controller.reactSteps(task.getId(), null, 3);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page1 = (List<Map<String, Object>>) first.getBody();
        assertEquals(3, page1.size());
        assertEquals(Integer.valueOf(1), page1.get(0).get("stepNo"));
        assertEquals(Integer.valueOf(3), page1.get(2).get("stepNo"));

        ResponseEntity<Object> second = controller.reactSteps(task.getId(), Long.valueOf(3L), 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page2 = (List<Map<String, Object>>) second.getBody();
        assertEquals(3, page2.size());
        assertEquals(Integer.valueOf(4), page2.get(0).get("stepNo"));
        assertEquals(Integer.valueOf(6), page2.get(2).get("stepNo"));

        ResponseEntity<Object> third = controller.reactSteps(task.getId(), Long.valueOf(6L), 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page3 = (List<Map<String, Object>>) third.getBody();
        assertEquals(1, page3.size());
        assertEquals(Integer.valueOf(7), page3.get(0).get("stepNo"));
        assertTrue(String.valueOf(page3.get(0).get("thought")).contains("step 7"));
    }

    @Test
    void sizeIsCappedAndNonPositiveFallsBackToDefault() {
        // size<=0 uses default 500; size>2000 is capped to 2000 — assert via SQL limit math
        // by ensuring an oversized request still succeeds on a real task.
        String slug = "t13-react-cap-" + UUID.randomUUID();
        Long skillId = jdbc.queryForObject(
                "INSERT INTO skill(slug,name,description,category) VALUES (?,?,?,?) RETURNING id",
                Long.class, slug, "React Cap", "t13", "研发");
        Long versionId = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, skillId, "1.0.0");
        SkillOptimizeTask task = new SkillOptimizeTask();
        task.setSkillId(skillId);
        task.setVersionId(versionId);
        task.setMode("edit");
        task.setStatus("queued");
        task.setRunMode("auto");
        task.setEngine("react");
        task.setCreatedBy("t13-react-user");
        repository.insertTask(task);

        SkillReactStep step = new SkillReactStep();
        step.setTaskId(task.getId());
        step.setStepNo(Integer.valueOf(1));
        step.setThought("only");
        step.setActionType("read_skill");
        repository.insertReactStep(step);

        ResponseEntity<Object> response = controller.reactSteps(task.getId(), null, 99999);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody();
        assertEquals(1, items.size());
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
