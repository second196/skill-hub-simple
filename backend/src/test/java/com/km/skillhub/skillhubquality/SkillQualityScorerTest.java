package com.km.skillhub.skillhubquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.analysis.AnalysisModelClient;
import com.km.skillhub.skillhubquality.SkillEffectAnalyzer.ObservationMetrics;
import com.km.skillhub.skillhubquality.SkillQualityScorer.Dimension;
import com.km.skillhub.skillhubquality.SkillQualityScorer.QualityReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 九维评分器：维度定义/权重、gap 与短板排序、维度 8「实测表现」折算、
 * 模型不可用降级。
 */
class SkillQualityScorerTest {

    private static final String[] SCORES = {"8", "7", "6", "9", "5", "8", "7", "9", "6"};

    private final ObjectMapper mapper = new ObjectMapper();

    private String scorerJson() {
        StringBuilder builder = new StringBuilder("{\"dims\":[");
        for (int i = 0; i < SCORES.length; i++) {
            if (i > 0) builder.append(',');
            builder.append("{\"score\":").append(SCORES[i])
                    .append(",\"reason\":\"理由").append(i)
                    .append("\",\"suggestion\":\"改法").append(i).append("\"}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private SkillQualityScorer scorerWithModel(final String raw) {
        AnalysisModelClient client = new AnalysisModelClient(
                mapper, true, "http://model.example", "key", "model", "emb", 1000, 500, 1000) {
            @Override
            public Optional<String> chat(String slug, String system, String user) {
                return Optional.of(raw);
            }
        };
        return new SkillQualityScorer(client, mapper);
    }

    private SkillQualityScorer scorerWithoutModel() {
        AnalysisModelClient client = new AnalysisModelClient(
                mapper, false, "", "", "model", "emb", 1000, 500, 1000);
        return new SkillQualityScorer(client, mapper);
    }

    @Test
    void nineDimensionsCarryNamesWeightsGapAndSortedShortboard() {
        QualityReport report = scorerWithModel(scorerJson()).fromRaw(scorerJson(), null);

        List<Dimension> dims = report.getDims();
        assertEquals(SkillQualityScorer.DIMENSION_COUNT, dims.size());
        for (int i = 0; i < SkillQualityScorer.DIMENSION_COUNT; i++) {
            assertEquals(SkillQualityScorer.DIMENSION_NAMES[i], dims.get(i).getName());
            assertEquals(SkillQualityScorer.DIMENSION_WEIGHTS[i], dims.get(i).getWeight());
        }
        // total = Σ(分×权重/10) = 71.5
        assertEquals(71.5d, report.getTotal(), 0.0001d);
        // gap = 权重×(10−分)/10
        assertEquals(1.4d, dims.get(0).getGap(), 0.0001d);
        assertEquals(9.0d, dims.get(4).getGap(), 0.0001d);

        // 短板按 gap 降序：可执行具体性(9.0) 最先
        List<Dimension> shortboard = report.getShortboard();
        assertFalse(shortboard.isEmpty());
        assertEquals("可执行具体性", shortboard.get(0).getName());
        for (int i = 1; i < shortboard.size(); i++) {
            assertTrue(shortboard.get(i - 1).getGap() >= shortboard.get(i).getGap());
        }
        // 无实测证据 → dry_run；模型给过理由则保留模型理由
        assertEquals(SkillQualityScorer.EVAL_MODE_DRY_RUN, report.getEvalMode());
        assertEquals("理由7", dims.get(SkillQualityScorer.INDEX_EFFECT).getReason());
        // 模型未给理由时补 dry_run 说明
        QualityReport bare = scorerWithModel("{\"scores\":[8,8,8,8,8,8,8,8,8]}")
                .fromRaw("{\"scores\":[8,8,8,8,8,8,8,8,8]}", null);
        assertTrue(bare.getDims().get(SkillQualityScorer.INDEX_EFFECT).getReason()
                .contains("无 L3 实测证据"));
        assertFalse(report.getSuggestions().isEmpty());
    }

    @Test
    void effectDimensionFoldedFromObservationMetrics() {
        ObservationMetrics metrics = new ObservationMetrics(
                10, 8, 3, 5, 0, 0.5d, 0.0d, 1.0d, 500L);
        QualityReport report = scorerWithModel(scorerJson()).fromRaw(scorerJson(), metrics);

        Dimension effectDim = report.getDims().get(SkillQualityScorer.INDEX_EFFECT);
        // 0.4×(1−0.5) + 0.3×(1−0) + 0.3×1.0 = 0.8 → 8 分，覆盖 LLM 的 9 分
        assertEquals(8, effectDim.getScore());
        assertTrue(effectDim.getReason().contains("折算"));
        assertTrue(effectDim.getReason().contains("50%"));
        // 有证据 → evalMode=llm
        assertEquals(SkillQualityScorer.EVAL_MODE_LLM, report.getEvalMode());
        // 其余维度保持 LLM 分
        assertEquals(8, report.getDims().get(0).getScore());
        // 重算后的 total：71.5 − 9×23/10 + 8×23/10 = 69.2
        assertEquals(69.2d, report.getTotal(), 0.0001d);
    }

    @Test
    void foldEffectScoreMapsMetricsToScale() {
        // 全部最优 → 10
        assertEquals(10, SkillQualityScorer.foldEffectScore(
                new ObservationMetrics(9, 9, 2, 0, 0, 0d, 0d, 1.0d, 100L)));
        // 全部最差 → 下限 1
        assertEquals(1, SkillQualityScorer.foldEffectScore(
                new ObservationMetrics(9, 9, 2, 9, 9, 1.0d, 1.0d, 0.0d, 0L)));
        // 无清单覆盖时按 0.6/0.4 归一：error=0.5 → 0.6×0.5+0.4×1 = 0.7 → 7
        assertEquals(7, SkillQualityScorer.foldEffectScore(
                new ObservationMetrics(9, 9, 2, 4, 0, 0.5d, 0d, -1d, 0L)));
        // 无数据 → 中位 5
        assertEquals(5, SkillQualityScorer.foldEffectScore(ObservationMetrics.unavailable()));
    }

    @Test
    void degradesToUnavailableWhenModelDisabled() {
        QualityReport report = scorerWithoutModel().score("anything", null);

        assertEquals(SkillQualityScorer.EVAL_MODE_UNAVAILABLE, report.getEvalMode());
        assertEquals(SkillQualityScorer.DIMENSION_COUNT, report.getDims().size());
        for (Dimension dim : report.getDims()) {
            assertEquals(0, dim.getScore());
            assertNotNull(dim.getReason());
            assertTrue(dim.getReason().contains("不可用"));
        }
        assertEquals(0d, report.getTotal(), 0.0001d);
        assertTrue(report.getShortboard().isEmpty());
        assertTrue(report.getSuggestions().isEmpty());
    }

    @Test
    void gapAndTotalHelpersFollowContract() {
        assertEquals(0d, SkillQualityScorer.gapOf(23, 10), 0.0001d);
        // 分数先收敛到 1..10：0 → 1 → 23×9/10 = 20.7（与 kmplm clamp 一致）
        assertEquals(20.7d, SkillQualityScorer.gapOf(23, 0), 0.0001d);
        assertEquals(13.8d, SkillQualityScorer.gapOf(23, 4), 0.0001d);
        assertEquals(0d, SkillQualityScorer.gapOf(10, 99), 0.0001d);

        Dimension a = new Dimension("a", 60, 10, 0d, "", "");
        Dimension b = new Dimension("b", 40, 5, 20d, "", "");
        assertEquals(80d, SkillQualityScorer.totalOf(java.util.Arrays.asList(a, b)), 0.0001d);
        assertEquals(0d, SkillQualityScorer.totalOf(null), 0.0001d);
    }
}
