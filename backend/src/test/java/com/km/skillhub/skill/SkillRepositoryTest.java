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

        assertTrue(jdbc.sql.contains("s.download_count"));
        assertTrue(jdbc.sql.contains("s.status=?"));
        assertTrue(jdbc.sql.contains("v.version_label"));
        assertTrue(!jdbc.sql.toLowerCase().contains("version_digest"));
        assertArrayEquals(new Object[]{"OFFLINE"}, jdbc.args);
    }

    @Test
    void listDefaultsToActiveStatus() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkillRepository repository = new SkillRepository(jdbc);

        repository.list(null, null, null);

        assertTrue(jdbc.sql.contains("s.download_count"));
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

    @Test
    void incrementDownloadCountUsesAtomicUpdate() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.updateCount = 1;
        SkillRepository repository = new SkillRepository(jdbc);

        repository.incrementDownloadCount("sample-skill");

        assertEquals("UPDATE skill SET download_count=download_count+1 WHERE slug=?", jdbc.updateSql);
        assertArrayEquals(new Object[]{"sample-skill"}, jdbc.updateArgs);
    }

    @Test
    void versionIdAcceptsSemVerLabel() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.ids = Collections.singletonList(42L);
        SkillRepository repository = new SkillRepository(jdbc);

        assertEquals(42L, repository.versionId("sample-skill", "1.0.1"));
        assertTrue(jdbc.sql.contains("version_label"));
        assertArrayEquals(new Object[]{"sample-skill", "1.0.1"}, jdbc.args);
    }

    @Test
    void versionIdRejectsInvalidSemVer() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkillRepository repository = new SkillRepository(jdbc);
        SkillApiException error = assertThrows(SkillApiException.class, () -> repository.versionId("sample-skill", "latest"));
        assertEquals(SkillApiException.CODE_VERSION_INVALID, error.getCode());
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args = new Object[0];
        private String updateSql;
        private Object[] updateArgs = new Object[0];
        private int updateCount;
        private List<Long> ids = Collections.emptyList();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return Collections.emptyList();
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            this.sql = sql;
            this.args = args;
            @SuppressWarnings("unchecked")
            List<T> result = (List<T>) ids;
            return result;
        }

        @Override
        public int update(String sql, Object... args) {
            this.updateSql = sql;
            this.updateArgs = args;
            return updateCount;
        }
    }
}
