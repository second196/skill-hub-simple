package com.km.skillhub.skillhubquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code skill_quality_config} (key/value JSONB) with the same fallback
 * constants the V12 migration documents. Every read is fresh so a config edit
 * applies to the next analysis without a restart; DB failures degrade to the
 * defaults instead of failing the report.
 *
 * <p>Defaults: w1/w2/w3=0.2/0.4/0.4, optimizeThreshold/watchUpper=70/85,
 * minImprovement/maxRegression=0.02/0.02, editBudget=4, pairedN=3,
 * autoAccept=false, l3Method="SkillHone", chainEnabled=true.</p>
 */
@Service
public class SkillQualityConfigService {

    public static final String RULE_VERSION = "v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SkillQualityConfigService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    /** All config rows parsed; never throws, missing keys fall back to defaults. */
    public Map<String, Object> current() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT config_key, config_value::text AS config_value FROM skill_quality_config");
            for (Map<String, Object> row : rows) {
                Object key = row.get("config_key");
                if (key == null) continue;
                out.put(String.valueOf(key), parseValue(row.get("config_value")));
            }
        } catch (RuntimeException ex) {
            // DB unavailable → defaults only
        }
        return out;
    }

    /** 综合分权重：文件检查。 */
    public double w1() {
        return number("w1", 0.2d);
    }

    /** 综合分权重：写法评分。 */
    public double w2() {
        return number("w2", 0.4d);
    }

    /** 综合分权重：使用效果。 */
    public double w3() {
        return number("w3", 0.4d);
    }

    /** 综合分低于此值建议优化。 */
    public int optimizeThreshold() {
        return integer("optimizeThreshold", 70);
    }

    /** 综合分高于此值可保持。 */
    public int watchUpper() {
        return integer("watchUpper", 85);
    }

    /** 改进门：主指标相对基线最小提升。 */
    public double minImprovement() {
        return number("minImprovement", 0.02d);
    }

    /** 回归门：对照项允许的最大退化。 */
    public double maxRegression() {
        return number("maxRegression", 0.02d);
    }

    /** 编辑预算门：单轮最大编辑条数。 */
    public int editBudget() {
        return integer("editBudget", 4);
    }

    /** 对比门：darwin paired 评审次数。 */
    public int pairedN() {
        return integer("pairedN", 3);
    }

    /** 人审默认；自动采纳需管理员配置。 */
    public boolean autoAccept() {
        return bool("autoAccept", false);
    }

    /** L3 计分方法说明（默认 SkillHone，不使用 skillopt_sleep）。 */
    public String l3Method() {
        return text("l3Method", "SkillHone");
    }

    /**
     * skill链路 LLM 评分总开关：关闭时链路分析只做规则统计，
     * 不调用模型（预算受限环境的降级路径）。
     */
    public boolean chainEnabled() {
        return bool("chainEnabled", true);
    }

    /** 报告落库 rule_version 常量。 */
    public String ruleVersion() {
        return text("ruleVersion", RULE_VERSION);
    }

    // ------------------------------------------------------------- helpers

    private double number(String key, double fallback) {
        Object value = current().get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private int integer(String key, int fallback) {
        Object value = current().get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return (int) Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private boolean bool(String key, boolean fallback) {
        Object value = current().get(key);
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        return fallback;
    }

    private String text(String key, String fallback) {
        Object value = current().get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private Object parseValue(Object raw) {
        if (raw == null) return null;
        String text = String.valueOf(raw);
        String trimmed = text.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) return null;
        try {
            return mapper.readValue(trimmed, Object.class);
        } catch (Exception ex) {
            return trimmed;
        }
    }
}
