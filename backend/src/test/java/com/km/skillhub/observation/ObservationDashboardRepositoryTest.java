package com.km.skillhub.observation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL/parameter contract of the dashboard read queries, most importantly the
 * session full-text search over JSONB payload content.
 */
class ObservationDashboardRepositoryTest {

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        String sql;
        Object[] args = new Object[0];
        List<Map<String, Object>> rows = Collections.emptyList();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return rows;
        }
    }

    @Test
    void searchMatchesPayloadJsonbTextNameAndPath() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ObservationDashboardRepository repository = new ObservationDashboardRepository(jdbc);

        repository.searchHits(null, null, "部署脚本", 30);

        assertTrue(jdbc.sql.contains("st.payload#>>'{text}'"));
        assertTrue(jdbc.sql.contains("st.payload#>>'{name}'"));
        assertTrue(jdbc.sql.contains("st.payload#>>'{path}'"));
        assertTrue(jdbc.sql.contains("t.user_text"));
        assertTrue(jdbc.sql.contains("ILIKE"));
        assertTrue(jdbc.sql.contains("DISTINCT ON (sess.id)"));
        assertTrue(jdbc.sql.contains("LIMIT ?"));
        // query bound once per ILIKE branch, then the limit
        assertArrayEquals(new Object[]{"部署脚本", "部署脚本", "部署脚本", "部署脚本", Integer.valueOf(30)},
                jdbc.args);
        // no skill scope → no skill EXISTS clause
        assertFalse(jdbc.sql.contains("EXISTS (SELECT 1 FROM observation_step skill_st"));
    }

    @Test
    void searchWithSkillScopeAddsLegacySlugAndVersionPredicates() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ObservationDashboardRepository repository = new ObservationDashboardRepository(jdbc);

        repository.searchHits("demo-skill", "v1.2.3", "keyword", 10);

        assertTrue(jdbc.sql.contains("EXISTS (SELECT 1 FROM observation_step skill_st"));
        // legacy COALESCE slug fallback so old payloads stay searchable
        assertTrue(jdbc.sql.contains("COALESCE(NULLIF(TRIM(skill_st.skill_slug), ''), skill_st.payload->>'skill_slug')"));
        assertTrue(jdbc.sql.contains("'1.2.3'"));
        assertTrue(jdbc.sql.contains("'v1.2.3'"));
        assertEquals("keyword", jdbc.args[0]);
        assertEquals("demo-skill", jdbc.args[4]);
        assertEquals(Integer.valueOf(10), jdbc.args[5]);
    }

    @Test
    void searchUnknownVersionMatchesEmptyLabel() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ObservationDashboardRepository repository = new ObservationDashboardRepository(jdbc);

        repository.searchHits("demo-skill", "unknown", "kw", 5);

        assertTrue(jdbc.sql.contains("IS NULL OR TRIM("));
        assertFalse(jdbc.sql.contains("'vunknown'"));
    }

    @Test
    void dashboardTurnsFiltersByLegacySkillAndLimits() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ObservationDashboardRepository repository = new ObservationDashboardRepository(jdbc);

        repository.dashboardTurns(Arrays.asList(1L, 2L), "demo-skill", "1.0.0", 200);

        assertTrue(jdbc.sql.contains("EXISTS (SELECT 1 FROM observation_step st"));
        assertTrue(jdbc.sql.contains("COALESCE(NULLIF(TRIM(st.skill_slug), ''), st.payload->>'skill_slug')"));
        assertTrue(jdbc.sql.contains("LIMIT ?"));
        assertEquals(Integer.valueOf(200), jdbc.args[jdbc.args.length - 1]);
    }

    @Test
    void dashboardTurnsWithoutSessionsSkipsTheQuery() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ObservationDashboardRepository repository = new ObservationDashboardRepository(jdbc);

        List<Map<String, Object>> rows = repository.dashboardTurns(
                Collections.<Long>emptyList(), null, null, 10);
        assertTrue(rows.isEmpty());
        assertEquals(null, jdbc.sql);
    }

    @Test
    void nodeLookupBindsSessionTurnAndStepIdentity() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ObservationDashboardRepository repository = new ObservationDashboardRepository(jdbc);

        repository.dashboardNode(5L, 3, "step-9");

        assertTrue(jdbc.sql.contains("t.session_id = ?"));
        assertTrue(jdbc.sql.contains("t.turn_index = ?"));
        assertTrue(jdbc.sql.contains("st.step_id = ?"));
        assertArrayEquals(new Object[]{Long.valueOf(5L), Integer.valueOf(3), "step-9"}, jdbc.args);
    }
}
