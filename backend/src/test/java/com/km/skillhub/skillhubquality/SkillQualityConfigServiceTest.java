package com.km.skillhub.skillhubquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * skill_quality_config 读取：缺省兜底、DB 值应用（权重/阈值/开关）、
 * DB 不可用时回退默认。
 */
class SkillQualityConfigServiceTest {

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        boolean fail;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (fail) throw new IllegalStateException("db down");
            return rows;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            // JdbcTemplate 的无参重载不会转发到 varargs 版本，这里显式桥接
            return queryForList(sql, new Object[0]);
        }
    }

    private static Map<String, Object> row(String key, String value) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("config_key", key);
        row.put("config_value", value);
        return row;
    }

    private final RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    private final SkillQualityConfigService config =
            new SkillQualityConfigService(jdbc, new ObjectMapper());

    @Test
    void fallsBackToDocumentedDefaultsWhenTableEmpty() {
        assertEquals(0.2d, config.w1(), 0.0001d);
        assertEquals(0.4d, config.w2(), 0.0001d);
        assertEquals(0.4d, config.w3(), 0.0001d);
        assertEquals(70, config.optimizeThreshold());
        assertEquals(85, config.watchUpper());
        assertEquals(0.02d, config.minImprovement(), 0.0001d);
        assertEquals(4, config.editBudget());
        assertEquals(3, config.pairedN());
        assertFalse(config.autoAccept());
        assertEquals("SkillHone", config.l3Method());
        assertTrue(config.chainEnabled());
        assertEquals(SkillQualityConfigService.RULE_VERSION, config.ruleVersion());
    }

    @Test
    void appliesConfiguredWeightsThresholdsAndSwitches() {
        jdbc.rows.add(row("w1", "0.5"));
        jdbc.rows.add(row("w2", "0.25"));
        jdbc.rows.add(row("w3", "0.25"));
        jdbc.rows.add(row("optimizeThreshold", "60"));
        jdbc.rows.add(row("watchUpper", "95"));
        jdbc.rows.add(row("chainEnabled", "false"));
        jdbc.rows.add(row("autoAccept", "true"));
        jdbc.rows.add(row("editBudget", "3"));
        jdbc.rows.add(row("l3Method", "\"SkillHoneX\""));
        jdbc.rows.add(row("ruleVersion", "v9"));

        assertEquals(0.5d, config.w1(), 0.0001d);
        assertEquals(0.25d, config.w2(), 0.0001d);
        assertEquals(0.25d, config.w3(), 0.0001d);
        assertEquals(60, config.optimizeThreshold());
        assertEquals(95, config.watchUpper());
        assertFalse(config.chainEnabled());
        assertTrue(config.autoAccept());
        assertEquals(3, config.editBudget());
        assertEquals("SkillHoneX", config.l3Method());
        assertEquals("v9", config.ruleVersion());
    }

    @Test
    void invalidOrMissingValuesFallBackPerKey() {
        jdbc.rows.add(row("w1", "not-a-number"));
        jdbc.rows.add(row("watchUpper", null));

        assertEquals(0.2d, config.w1(), 0.0001d);
        assertEquals(85, config.watchUpper());
        assertEquals(0.4d, config.w2(), 0.0001d);
    }

    @Test
    void databaseFailureDegradesToDefaults() {
        jdbc.fail = true;

        assertEquals(0.2d, config.w1(), 0.0001d);
        assertEquals(70, config.optimizeThreshold());
        assertTrue(config.chainEnabled());
        assertTrue(config.current().isEmpty());
    }
}
