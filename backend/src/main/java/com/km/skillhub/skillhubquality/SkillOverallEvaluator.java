package com.km.skillhub.skillhubquality;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 综合质量分与是否需要优化。
 *
 * <p>综合分 = W1×L1 + W2×L2 + W3×L3，权重与阈值读
 * {@code skill_quality_config}（w1/w2/w3、optimizeThreshold/watchUpper，
 * 缺省 0.2 / 0.4 / 0.4 与 70 / 85），每次评估取最新配置。
 * L2/L3 缺失时降低对应权重并重归一；样本不足时降低效果权重。
 * 结论四态：NEED_OPTIMIZE / WATCH / KEEP / NO_DATA。
 * L1 有错误时强制 NEED_OPTIMIZE。</p>
 */
@Service
public class SkillOverallEvaluator {

    public static final String CONCLUSION_NEED_OPTIMIZE = "NEED_OPTIMIZE";
    public static final String CONCLUSION_WATCH = "WATCH";
    public static final String CONCLUSION_KEEP = "KEEP";
    public static final String CONCLUSION_NO_DATA = "NO_DATA";

    private final SkillQualityConfigService configService;

    public SkillOverallEvaluator(SkillQualityConfigService configService) {
        this.configService = configService;
    }

    /** Overall result. */
    public static class OverallReport {
        /** null when NO_DATA */
        private Integer overallScore;
        /** NEED_OPTIMIZE | WATCH | KEEP | NO_DATA */
        private String conclusion;
        private Map<String, Object> weightsUsed = new LinkedHashMap<String, Object>();

        public Integer getOverallScore() { return overallScore; }
        public void setOverallScore(Integer overallScore) { this.overallScore = overallScore; }
        public String getConclusion() { return conclusion; }
        public void setConclusion(String conclusion) { this.conclusion = conclusion; }
        public Map<String, Object> getWeightsUsed() { return weightsUsed; }
        public void setWeightsUsed(Map<String, Object> weightsUsed) {
            this.weightsUsed = weightsUsed == null ? new LinkedHashMap<String, Object>() : weightsUsed;
        }
    }

    /**
     * 综合判定。
     *
     * @param l1Score     文件检查分，可空
     * @param l1HasError  L1 是否存在错误项（有错误强制 NEED_OPTIMIZE）
     * @param l2Score     写法评分，可空
     * @param l3Score     使用效果分，可空
     * @param sampleOk    L3 样本是否充足
     */
    public OverallReport evaluate(Integer l1Score, boolean l1HasError,
                                  Integer l2Score, Integer l3Score, boolean sampleOk) {
        OverallReport report = new OverallReport();

        // Config values fall back to 0.2/0.4/0.4 inside the config service when
        // the key is missing; an explicit 0 in the table is honored (renormalized).
        double defaultW1 = configService.w1();
        double defaultW2 = configService.w2();
        double defaultW3 = configService.w3();
        int needOptimizeThreshold = configService.optimizeThreshold() <= 0
                ? 70 : configService.optimizeThreshold();
        int keepThreshold = configService.watchUpper() <= 0
                ? 85 : configService.watchUpper();

        boolean hasL1 = l1Score != null;
        boolean hasL2 = l2Score != null;
        boolean hasL3 = l3Score != null;
        if (!hasL1 && !hasL2 && !hasL3) {
            report.setOverallScore(null);
            report.setConclusion(CONCLUSION_NO_DATA);
            return report;
        }

        double w1 = hasL1 ? defaultW1 : 0d;
        double w2 = hasL2 ? defaultW2 : 0d;
        double w3 = hasL3 ? defaultW3 : 0d;
        if (hasL3 && !sampleOk) {
            // 样本不足：效果权重减半，并重归一
            w3 = w3 * 0.5d;
        }
        double total = w1 + w2 + w3;
        if (total <= 0d) {
            report.setOverallScore(null);
            report.setConclusion(CONCLUSION_NO_DATA);
            return report;
        }
        w1 = w1 / total;
        w2 = w2 / total;
        w3 = w3 / total;

        double weighted = 0d;
        if (hasL1) weighted += w1 * l1Score.doubleValue();
        if (hasL2) weighted += w2 * l2Score.doubleValue();
        if (hasL3) weighted += w3 * l3Score.doubleValue();
        int overall = (int) Math.round(weighted);

        Map<String, Object> weightsUsed = new LinkedHashMap<String, Object>();
        weightsUsed.put("w1", Double.valueOf(round3(w1)));
        weightsUsed.put("w2", Double.valueOf(round3(w2)));
        weightsUsed.put("w3", Double.valueOf(round3(w3)));
        weightsUsed.put("sampleOk", Boolean.valueOf(sampleOk));
        weightsUsed.put("l1HasError", Boolean.valueOf(l1HasError));
        weightsUsed.put("optimizeThreshold", Integer.valueOf(needOptimizeThreshold));
        weightsUsed.put("watchUpper", Integer.valueOf(keepThreshold));
        report.setWeightsUsed(weightsUsed);
        report.setOverallScore(Integer.valueOf(overall));

        report.setConclusion(resolveConclusion(overall, l1HasError, sampleOk,
                needOptimizeThreshold, keepThreshold));
        return report;
    }

    private static String resolveConclusion(int overall, boolean l1HasError, boolean sampleOk,
                                            int needOptimizeThreshold, int keepThreshold) {
        if (l1HasError || overall < needOptimizeThreshold) {
            return CONCLUSION_NEED_OPTIMIZE;
        }
        if (!sampleOk || overall < keepThreshold) {
            return CONCLUSION_WATCH;
        }
        return CONCLUSION_KEEP;
    }

    private static double round3(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
