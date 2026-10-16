package com.km.skillhub.skillhubquality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 综合分：默认权重 0.2/0.4/0.4、重归一、样本不足压权重并封顶 WATCH、
 * L1 错误强制 NEED_OPTIMIZE，以及 skill_quality_config 权重/阈值的应用。
 */
class SkillQualityOverallEvaluatorTest {

    /** 固定配置的轻量替身：只覆盖评估用到的读取。 */
    private static final class FixedConfig extends SkillQualityConfigService {
        double w1 = 0.2d;
        double w2 = 0.4d;
        double w3 = 0.4d;
        int optimizeThreshold = 70;
        int watchUpper = 85;

        private FixedConfig() {
            super(null, null);
        }

        @Override public double w1() { return w1; }
        @Override public double w2() { return w2; }
        @Override public double w3() { return w3; }
        @Override public int optimizeThreshold() { return optimizeThreshold; }
        @Override public int watchUpper() { return watchUpper; }
    }

    private final FixedConfig config = new FixedConfig();
    private final SkillOverallEvaluator evaluator = new SkillOverallEvaluator(config);

    @Test
    void combinesSectionsWithDefaultWeights() {
        SkillOverallEvaluator.OverallReport report = evaluator.evaluate(80, false, 80, 60, true);

        // 0.2×80 + 0.4×80 + 0.4×60 = 72
        assertEquals(Integer.valueOf(72), report.getOverallScore());
        assertEquals(SkillOverallEvaluator.CONCLUSION_WATCH, report.getConclusion());
        assertEquals(Double.valueOf(0.2), report.getWeightsUsed().get("w1"));
        assertEquals(Double.valueOf(0.4), report.getWeightsUsed().get("w2"));
        assertEquals(Double.valueOf(0.4), report.getWeightsUsed().get("w3"));
    }

    @Test
    void conclusionFollowsThresholds() {
        assertEquals(SkillOverallEvaluator.CONCLUSION_KEEP,
                evaluator.evaluate(90, false, 90, 90, true).getConclusion());
        assertEquals(SkillOverallEvaluator.CONCLUSION_NEED_OPTIMIZE,
                evaluator.evaluate(60, false, 60, 60, true).getConclusion());
        // L1 有错误 → 强制 NEED_OPTIMIZE，即使分数很高
        assertEquals(SkillOverallEvaluator.CONCLUSION_NEED_OPTIMIZE,
                evaluator.evaluate(95, true, 95, 95, true).getConclusion());
        // 无任何分 → NO_DATA
        SkillOverallEvaluator.OverallReport empty = evaluator.evaluate(null, false, null, null, true);
        assertEquals(SkillOverallEvaluator.CONCLUSION_NO_DATA, empty.getConclusion());
        assertNull(empty.getOverallScore());
    }

    @Test
    void missingSectionsRenormalizeAndSampleShortageCapsConclusion() {
        // L3 缺失：权重只在 L1/L2 间重归一 → 0.2/0.6 与 0.4/0.6 → 加权仍 80
        SkillOverallEvaluator.OverallReport withoutL3 = evaluator.evaluate(80, false, 80, null, true);
        assertEquals(Integer.valueOf(80), withoutL3.getOverallScore());
        assertEquals(0.333d, ((Double) withoutL3.getWeightsUsed().get("w1")).doubleValue(), 0.001d);
        assertEquals(0.667d, ((Double) withoutL3.getWeightsUsed().get("w2")).doubleValue(), 0.001d);

        // 样本不足：w3 减半再重归一（0.4→0.2，总 0.8 → 0.25），结论最高 WATCH
        SkillOverallEvaluator.OverallReport thin = evaluator.evaluate(95, false, 95, 95, false);
        assertEquals(SkillOverallEvaluator.CONCLUSION_WATCH, thin.getConclusion());
        assertEquals(0.25d, ((Double) thin.getWeightsUsed().get("w3")).doubleValue(), 0.001d);
    }

    @Test
    void appliesConfiguredWeightsAndThresholds() {
        // 权重来自配置：w1=1、w2=w3=0 → 综合分 = L1
        config.w1 = 1d;
        config.w2 = 0d;
        config.w3 = 0d;
        SkillOverallEvaluator.OverallReport report = evaluator.evaluate(77, false, 80, 60, true);
        assertEquals(Integer.valueOf(77), report.getOverallScore());

        // 默认阈值 85 下 77 是 WATCH；改配置 watchUpper=70 → KEEP
        assertEquals(SkillOverallEvaluator.CONCLUSION_WATCH, report.getConclusion());
        config.watchUpper = 70;
        assertEquals(SkillOverallEvaluator.CONCLUSION_KEEP, evaluator
                .evaluate(77, false, 80, 60, true).getConclusion());

        // optimizeThreshold=90 → 77 低于阈值 → NEED_OPTIMIZE
        config.optimizeThreshold = 90;
        assertEquals(SkillOverallEvaluator.CONCLUSION_NEED_OPTIMIZE, evaluator
                .evaluate(77, false, 80, 60, true).getConclusion());
    }
}
