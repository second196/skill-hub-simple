package com.km.skillhub.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.analysis.ObservationAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Session-scoped analysis: rule hits are restricted to one session's turns and the
 * report carries the session identity.
 */
class ObservationSessionAnalysisServiceTest {

    private static final class StubJdbcTemplate extends JdbcTemplate {
        List<Map<String, Object>> turnRows = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> stepRows = new ArrayList<Map<String, Object>>();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("FROM observation_session")) {
                Object requested = args.length > 0 ? args[0] : null;
                if (!(requested instanceof Number) || ((Number) requested).longValue() != 7L) {
                    return Collections.emptyList();
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("id", Long.valueOf(7L));
                row.put("session_key", "sess-7");
                row.put("client_name", "codex");
                return Collections.singletonList(row);
            }
            if (sql.contains("FROM observation_turn")) {
                return turnRows;
            }
            if (sql.contains("FROM observation_step")) {
                return stepRows;
            }
            return Collections.emptyList();
        }
    }

    private static Map<String, Object> stepRow(long turnId, int seq, String type,
                                               String slug, Map<String, Object> payload) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("turn_id", Long.valueOf(turnId));
        row.put("seq", Integer.valueOf(seq));
        row.put("type", type);
        row.put("skill_slug", slug);
        row.put("skill_version_label", "1.0.0");
        row.put("payload", payload);
        return row;
    }

    private static Map<String, Object> turnRow(long id, int index) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Long.valueOf(id));
        row.put("turn_index", Integer.valueOf(index));
        row.put("session_id", Long.valueOf(7L));
        row.put("user_text", "试一下这个技能");
        row.put("client_id", "c1");
        return row;
    }

    private static List<String> ruleIds(Map<String, Object> report) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problems = (List<Map<String, Object>>) report.get("problems");
        List<String> ids = new ArrayList<String>();
        for (Map<String, Object> problem : problems) {
            ids.add(String.valueOf(problem.get("ruleId")));
        }
        return ids;
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsSessionScopedRuleHits() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.turnRows.add(turnRow(10L, 0));
        jdbc.turnRows.add(turnRow(11L, 1));
        jdbc.turnRows.add(turnRow(12L, 2));
        Map<String, Object> errorPayload = new LinkedHashMap<String, Object>();
        errorPayload.put("error", Boolean.TRUE);
        // turn 10: skill load errors and nothing follows → R1 + R2
        jdbc.stepRows.add(stepRow(10L, 1, "skill", "demo", errorPayload));
        // turn 11: skill loaded twice, no follow-up action → R2 + R3
        jdbc.stepRows.add(stepRow(11L, 1, "skill", "demo", new LinkedHashMap<String, Object>()));
        jdbc.stepRows.add(stepRow(11L, 2, "skill", "demo", new LinkedHashMap<String, Object>()));
        // turn 12: two different skills in one turn → R2 + R4
        jdbc.stepRows.add(stepRow(12L, 1, "skill", "demo", new LinkedHashMap<String, Object>()));
        jdbc.stepRows.add(stepRow(12L, 2, "skill", "other", new LinkedHashMap<String, Object>()));

        ObservationAnalyzer analyzer = mock(ObservationAnalyzer.class);
        when(analyzer.ensureContract(eq("demo"), eq("1.0.0"), anyBoolean())).thenReturn(null);
        ObservationSessionAnalysisService service = new ObservationSessionAnalysisService(
                jdbc, analyzer, new ObjectMapper());

        Map<String, Object> report = service.analyze(7L, "demo", "1.0.0", false);

        assertEquals(Long.valueOf(7L), report.get("sessionId"));
        assertEquals("demo", report.get("slug"));
        assertEquals("1.0.0", report.get("versionLabel"));
        assertEquals("session", report.get("analysisScope"));
        assertFalse(report.containsKey("degraded"));

        List<String> rules = ruleIds(report);
        assertTrue(rules.contains("R1"));
        assertTrue(rules.contains("R2"));
        assertTrue(rules.contains("R3"));
        assertTrue(rules.contains("R4"));

        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertEquals(Integer.valueOf(3), summary.get("turnsAnalyzed"));
        assertEquals("session", summary.get("analysisScope"));
        assertEquals(Integer.valueOf(0), summary.get("coverageTurns"));

        Map<String, Object> coverage = (Map<String, Object>) report.get("coverage");
        assertEquals(Boolean.FALSE, coverage.get("hasContract"));
    }

    @Test
    void rejectsBlankSkillAndUnknownSession() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        ObservationSessionAnalysisService service = new ObservationSessionAnalysisService(
                jdbc, mock(ObservationAnalyzer.class), new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.analyze(7L, "  ", null, false));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(404L, "demo", "1.0.0", false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void degradesWithoutPublishedVersion() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        ObservationSessionAnalysisService service = new ObservationSessionAnalysisService(
                jdbc, mock(ObservationAnalyzer.class), new ObjectMapper());

        Map<String, Object> report = service.analyze(7L, "demo", null, false);

        assertEquals(Boolean.TRUE, report.get("degraded"));
        assertTrue(String.valueOf(report.get("message")).contains("版本"));
        assertTrue(((List<Object>) report.get("problems")).isEmpty());
    }
}
