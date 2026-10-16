package com.km.skillhub.skillhubquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.analysis.AnalysisModelClient;
import com.km.skillhub.skillhubquality.SkillEffectAnalyzer.ObservationMetrics;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L2 写法评分（darwin 九维）。
 *
 * <p>每维 1–10 分；total = Σ(分×权重/10)；短板 gap = 权重×(10−分)/10，按 gap 降序。
 * 维度 8「实测表现」优先由观测指标（errorRate/reloadRate/checklistCoverage）折算，
 * 无观测证据时走 dry_run（LLM 估分）；LLM 不可用时 evalMode=unavailable，
 * 综合判定仍由 {@link SkillOverallEvaluator} 在无 L2 分时继续计算。</p>
 */
@Service
public class SkillQualityScorer {

    public static final String[] DIMENSION_NAMES = {
            "Frontmatter",
            "工作流清晰度",
            "失败模式",
            "检查点",
            "可执行具体性",
            "资源整合",
            "整体架构",
            "实测表现",
            "反例黑名单"
    };

    /** 九维权重：7,12,12,6,18,4,12,23,6（合计 100）。 */
    public static final int[] DIMENSION_WEIGHTS = {7, 12, 12, 6, 18, 4, 12, 23, 6};

    public static final int DIMENSION_COUNT = 9;
    /** 维度 8「实测表现」下标。 */
    public static final int INDEX_EFFECT = 7;

    public static final String EVAL_MODE_LLM = "llm";
    public static final String EVAL_MODE_DRY_RUN = "dry_run";
    public static final String EVAL_MODE_UNAVAILABLE = "unavailable";

    /** One scored dimension (1–10) with weighted gap. */
    public static class Dimension {
        private String name;
        private int weight;
        private int score;
        private double gap;
        private String reason;
        private String suggestion;

        public Dimension() {
        }

        public Dimension(String name, int weight, int score, double gap, String reason, String suggestion) {
            this.name = name;
            this.weight = weight;
            this.score = score;
            this.gap = gap;
            this.reason = reason;
            this.suggestion = suggestion;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getWeight() { return weight; }
        public void setWeight(int weight) { this.weight = weight; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public double getGap() { return gap; }
        public void setGap(double gap) { this.gap = gap; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getSuggestion() { return suggestion; }
        public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    }

    /** L2 result: 9 dims + total + shortboard + suggestions + evalMode. */
    public static class QualityReport {
        private List<Dimension> dims = new ArrayList<Dimension>();
        private double total;
        private List<Dimension> shortboard = new ArrayList<Dimension>();
        private List<String> suggestions = new ArrayList<String>();
        /** llm | dry_run | unavailable */
        private String evalMode;

        public List<Dimension> getDims() { return dims; }
        public void setDims(List<Dimension> dims) {
            this.dims = dims == null ? new ArrayList<Dimension>() : dims;
        }
        public double getTotal() { return total; }
        public void setTotal(double total) { this.total = total; }
        public List<Dimension> getShortboard() { return shortboard; }
        public void setShortboard(List<Dimension> shortboard) {
            this.shortboard = shortboard == null ? new ArrayList<Dimension>() : shortboard;
        }
        public List<String> getSuggestions() { return suggestions; }
        public void setSuggestions(List<String> suggestions) {
            this.suggestions = suggestions == null ? new ArrayList<String>() : suggestions;
        }
        public String getEvalMode() { return evalMode; }
        public void setEvalMode(String evalMode) { this.evalMode = evalMode; }
    }

    private static final String SYSTEM_PROMPT =
            "你是技能质量评审。对给定的技能定义正文按九个维度打 1-10 分。"
                    + "只输出 JSON：{\"dims\":[{\"score\":8,\"reason\":\"具体理由\",\"suggestion\":\"改法\"}...]}，"
                    + "dims 固定 9 项，顺序：Frontmatter、工作流清晰度、失败模式、检查点、"
                    + "可执行具体性、资源整合、整体架构、实测表现、反例黑名单。";

    private final AnalysisModelClient modelClient;
    private final ObjectMapper mapper;

    public SkillQualityScorer(AnalysisModelClient modelClient, ObjectMapper mapper) {
        this.modelClient = modelClient;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    /**
     * 九维写法评分。
     *
     * @param skillContent skill 定义正文
     * @param metrics      L3 实测证据，可空/无数据；无证据时维度 8 走 dry_run
     */
    public QualityReport score(String skillContent, ObservationMetrics metrics) {
        String content = skillContent == null ? "" : skillContent;
        Optional<String> raw;
        try {
            String hint = effectHint(metrics);
            String user = (hint.isEmpty() ? "" : ("实测证据：" + hint + "\n"))
                    + "技能正文：\n" + content;
            raw = modelClient.chat("skill-quality", SYSTEM_PROMPT, user);
        } catch (RuntimeException ex) {
            // 预算受限 / 端点失败：降级为不可用，不向外抛
            return unavailableReport();
        }
        if (!raw.isPresent()) {
            return unavailableReport();
        }
        return fromRaw(raw.get(), metrics);
    }

    /**
     * Build the report from an LLM JSON payload (exposed for tests and for
     * re-parsing cached responses). Dimension 8 is folded from observation
     * metrics whenever evidence exists; otherwise the model score stands with a
     * dry_run note.
     */
    public QualityReport fromRaw(String rawText, ObservationMetrics metrics) {
        boolean effectEvidence = metrics != null && metrics.isAvailable();
        int[] scores = parseScores(rawText);
        String[] reasons = parseTexts(rawText, "reason");
        String[] suggestions = parseTexts(rawText, "suggestion");
        String evalMode = effectEvidence ? EVAL_MODE_LLM : EVAL_MODE_DRY_RUN;

        List<Dimension> dims = new ArrayList<Dimension>(DIMENSION_COUNT);
        for (int i = 0; i < DIMENSION_COUNT; i++) {
            int score = clampScore(scores[i]);
            String reason = reasons[i] == null ? "" : reasons[i];
            String suggestion = suggestions[i] == null ? "" : suggestions[i];
            if (i == INDEX_EFFECT) {
                if (effectEvidence) {
                    score = foldEffectScore(metrics);
                    reason = "由观测指标折算：出错 " + percent(metrics.getErrorRate())
                            + "，重复载入 " + percent(metrics.getReloadRate())
                            + "，步骤完成度 " + (metrics.getChecklistCoverage() < 0d
                                    ? "暂无" : percent(metrics.getChecklistCoverage()));
                    if (suggestion.isEmpty()) {
                        suggestion = metrics.getErrorRate() > 0.1d
                                ? "优先消除高频出错：核对命令、脚本路径与参数示例"
                                : "保持实测表现，继续补充观测样本";
                    }
                } else {
                    if (reason.isEmpty()) {
                        reason = "无 L3 实测证据，按 dry_run 估分";
                    }
                    if (suggestion.isEmpty()) {
                        suggestion = "补充使用观测证据后再折算实测表现";
                    }
                }
            }
            double gap = gapOf(DIMENSION_WEIGHTS[i], score);
            dims.add(new Dimension(DIMENSION_NAMES[i], DIMENSION_WEIGHTS[i], score, gap, reason, suggestion));
        }
        return buildReport(dims, evalMode);
    }

    /** 无模型时的降级报告：九维 0 分、evalMode=unavailable。 */
    public QualityReport unavailableReport() {
        List<Dimension> dims = new ArrayList<Dimension>(DIMENSION_COUNT);
        for (int i = 0; i < DIMENSION_COUNT; i++) {
            dims.add(new Dimension(DIMENSION_NAMES[i], DIMENSION_WEIGHTS[i], 0, 0d,
                    "写法评分不可用", ""));
        }
        return buildReport(dims, EVAL_MODE_UNAVAILABLE);
    }

    /**
     * 维度 8「实测表现」折算：evidence = 0.4×(1−出错率) + 0.3×(1−重复载入率)
     * + 0.3×步骤完成度（无清单覆盖时前两项按 0.6/0.4 归一），映射到 1–10 分。
     */
    public static int foldEffectScore(ObservationMetrics metrics) {
        if (metrics == null || !metrics.isAvailable()) {
            return 5;
        }
        double reliability = 0.4d * (1d - metrics.getErrorRate())
                + 0.3d * (1d - metrics.getReloadRate());
        double value;
        if (metrics.getChecklistCoverage() < 0d) {
            value = 0.6d * (1d - metrics.getErrorRate()) + 0.4d * (1d - metrics.getReloadRate());
        } else {
            value = reliability + 0.3d * metrics.getChecklistCoverage();
        }
        if (value < 0d) value = 0d;
        if (value > 1d) value = 1d;
        int score = (int) Math.round(value * 10d);
        if (score < 1) score = 1;
        if (score > 10) score = 10;
        return score;
    }

    /** gap = 权重 × (10 − 分) / 10。 */
    public static double gapOf(int weight, int score) {
        int clamped = clampScore(score);
        return weight * (10 - clamped) / 10.0d;
    }

    /** total = Σ(分 × 权重 / 10)。 */
    public static double totalOf(List<Dimension> dims) {
        if (dims == null || dims.isEmpty()) return 0d;
        double total = 0d;
        for (Dimension dim : dims) {
            total += dim.getScore() * dim.getWeight() / 10.0d;
        }
        return total;
    }

    private QualityReport buildReport(List<Dimension> dims, String evalMode) {
        QualityReport report = new QualityReport();
        report.setDims(dims);
        report.setTotal(round1(totalOf(dims)));
        report.setEvalMode(evalMode);

        List<Dimension> shortboard = new ArrayList<Dimension>();
        for (Dimension dim : dims) {
            if (dim.getGap() > 0d) {
                shortboard.add(dim);
            }
        }
        Collections.sort(shortboard, new Comparator<Dimension>() {
            @Override
            public int compare(Dimension a, Dimension b) {
                int byGap = Double.compare(b.getGap(), a.getGap());
                if (byGap != 0) return byGap;
                return Double.compare(b.getWeight(), a.getWeight());
            }
        });
        report.setShortboard(shortboard);

        List<String> suggestions = new ArrayList<String>();
        for (Dimension dim : shortboard) {
            String text = dim.getSuggestion();
            if (text == null || text.isEmpty()) {
                text = "改进「" + dim.getName() + "」：当前 " + dim.getScore() + " 分，加权缺口 " + round1(dim.getGap());
            }
            suggestions.add(text);
        }
        report.setSuggestions(suggestions);
        return report;
    }

    private static String effectHint(ObservationMetrics metrics) {
        if (metrics == null || !metrics.isAvailable()) {
            return "";
        }
        return "errorRate=" + metrics.getErrorRate()
                + ", reloadRate=" + metrics.getReloadRate()
                + ", checklistCoverage=" + metrics.getChecklistCoverage();
    }

    private static String percent(double value) {
        return Math.round(value * 100d) + "%";
    }

    @SuppressWarnings("unchecked")
    private int[] parseScores(String rawText) {
        int[] scores = new int[DIMENSION_COUNT];
        for (int i = 0; i < DIMENSION_COUNT; i++) {
            scores[i] = 5;
        }
        Map<String, Object> root = parseJsonObject(rawText);
        if (root.isEmpty()) {
            return scores;
        }
        Object dimsNode = root.get("dims");
        if (dimsNode instanceof List) {
            List<Object> list = (List<Object>) dimsNode;
            for (int i = 0; i < DIMENSION_COUNT && i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Map) {
                    Object score = ((Map<String, Object>) item).get("score");
                    if (score instanceof Number) {
                        scores[i] = ((Number) score).intValue();
                    }
                } else if (item instanceof Number) {
                    scores[i] = ((Number) item).intValue();
                }
            }
            return scores;
        }
        Object scoresNode = root.get("scores");
        if (scoresNode instanceof List) {
            List<Object> list = (List<Object>) scoresNode;
            for (int i = 0; i < DIMENSION_COUNT && i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Number) {
                    scores[i] = ((Number) item).intValue();
                }
            }
        }
        return scores;
    }

    @SuppressWarnings("unchecked")
    private String[] parseTexts(String rawText, String field) {
        String[] texts = new String[DIMENSION_COUNT];
        Map<String, Object> root = parseJsonObject(rawText);
        Object dimsNode = root.get("dims");
        if (!(dimsNode instanceof List)) {
            return texts;
        }
        List<Object> list = (List<Object>) dimsNode;
        for (int i = 0; i < DIMENSION_COUNT && i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map) {
                Object value = ((Map<String, Object>) item).get(field);
                if (value != null) {
                    texts[i] = String.valueOf(value);
                }
            }
        }
        return texts;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyMap();
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) return Collections.emptyMap();
        try {
            return mapper.readValue(trimmed.substring(start, end + 1), Map.class);
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    private static int clampScore(int score) {
        if (score < 1) return 1;
        if (score > 10) return 10;
        return score;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0d) / 10.0d;
    }
}
