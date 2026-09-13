package com.km.skillhub.skill;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRepositoryTest {
    @Test
    void listUsesOnlyRequestedOfflineStatus() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkillRepository repository = new SkillRepository(jdbc);

        repository.list(null, null, "OFFLINE");

        assertTrue(jdbc.sql.contains("s.status=?"));
        assertArrayEquals(new Object[]{"OFFLINE"}, jdbc.args);
    }

    @Test
    void listDefaultsToActiveStatus() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkillRepository repository = new SkillRepository(jdbc);

        repository.list(null, null, null);

        assertTrue(jdbc.sql.contains("s.status=?"));
        assertArrayEquals(new Object[]{"ACTIVE"}, jdbc.args);
    }

    @Test
    void deleteRejectsUnknownSkill() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.updateCount = 0;
        SkillRepository repository = new SkillRepository(jdbc);

        assertThrows(IllegalArgumentException.class, () -> repository.delete("missing-skill"));
    }

    @Test
    void deleteRemovesSkillBySlug() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.updateCount = 1;
        SkillRepository repository = new SkillRepository(jdbc);

        repository.delete("sample-skill");

        assertEquals("DELETE FROM skill WHERE slug=?", jdbc.updateSql);
        assertArrayEquals(new Object[]{"sample-skill"}, jdbc.updateArgs);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args = new Object[0];
        private String updateSql;
        private Object[] updateArgs = new Object[0];
        private int updateCount;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return Collections.emptyList();
        }

        @Override
        public int update(String sql, Object... args) {
            this.updateSql = sql;
            this.updateArgs = args;
            return updateCount;
        }
    }
}
