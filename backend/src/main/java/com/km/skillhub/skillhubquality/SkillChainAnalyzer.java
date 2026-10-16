package com.km.skillhub.skillhubquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.analysis.AnalysisModelClient;
import com.km.skillhub.skillhubquality.SkillEffectAnalyzer.ObservationMetrics;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * skill链路分析：在某 Skill 版本的行为指标上按固定六维做程序统计 +
 * AI 归纳，输出每维得分、问题与修复意见。AI 失败（未配置 / 预算受限 /
 * 端点异常）时保持规则结果，绝不向外抛错——scope=chain 的降级路径。
 */
@Service
public class SkillChainAnalyzer {

    public static final String DIM_LOAD = "load_trigger";
    public static final String DIM_STEP = "step_completion";
    public static final String DIM_EXEC = "execution_correctness";
    public static final String DIM_FAIL = "failure_handling";
    public static final String DIM_ALIGN = "text_behavior_alignment";
    public static final String DIM_EFF = "efficiency";

    /** 维度顺序与展示名、权重、说明（权重和为 100）。 */
    public static final String[][] DIMENSIONS = {
            {DIM_LOAD, "载入与触发", "15", "技能是否被正确加载与触发"},
            {DIM_STEP, "步骤完整性", "25", "是否按技能步骤完整执行"},
            {DIM_EXEC, "执行正确性", "25", "工具与命令执行是否正确"},
            {DIM_FAIL, "失败处理", "15", "出错时说明是否给出可用处理"},
            {DIM_ALIGN, "说明与行为一致", "10", "实际行为与技能说明是否一致"},
            {DIM_EFF, "调用效率", "10", "步骤与消耗是否合理"},
    };

    public static class DimResult {
        private String key;
        private String name;
        private int weight;
        private String description;
        private int score;
        private List<String> problems = new ArrayList<String>();
        private List<String> fixes = new ArrayList<String>();
        private List<Map<String, Object>> issueRows = new ArrayList<Map<String, Object>>();
        private String source;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getWeight() { return weight; }
        public void setWeight(int weight) { this.weight = weight; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public List<String> getProblems() { return problems; }
        public void setProblems(List<String> problems) {
            this.problems = problems == null ? new ArrayList<String>() : problems;
        }
        public List<String> getFixes() { return fixes; }
        public void setFixes(List<String> fixes) {
            this.fixes = fixes == null ? new ArrayList<String>() : fixes;
        }
        public List<Map<String, Object>> getIssueRows() { return issueRows; }
        public void setIssueRows(List<Map<String, Object>> issueRows) {
            this.issueRows = issueRows == null ? new ArrayList<Map<String, Object>>() : issueRows;
        }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    public static class ChainReport {
        private List<DimResult> dimensions = new ArrayList<DimResult>();
        private List<Map<String, Object>> issueRows = new ArrayList<Map<String, Object>>();
        private int score;
        private Map<String, Object> stats = new LinkedHashMap<String, Object>();
        /** rule | ai+rule */
        private String source = "rule";

        public List<DimResult> getDimensions() { return dimensions; }
        public void setDimensions(List<DimResult> dimensions) {
            this.dimensions = dimensions == null ? new ArrayList<DimResult>() : dimensions;
        }
        public List<Map<String, Object>> getIssueRows() { return issueRows; }
        public void setIssueRows(List<Map<String, Object>> issueRows) {
            this.issueRows = issueRows == null ? new ArrayList<Map<String, Object>>() : issueRows;
        }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public Map<String, Object> getStats() { return stats; }
        public void setStats(Map<String, Object> stats) {
            this.stats = stats == null ? new LinkedHashMap<String, Object>() : stats;
        }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    private final AnalysisModelClient modelClient;
    private final ObjectMapper mapper;
    private final SkillQualityConfigService configService;

    public SkillChainAnalyzer(AnalysisModelClient modelClient, ObjectMapper mapper,
                              SkillQualityConfigService configService) {
        this.modelClient = modelClient;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.configService = configService;
    }

    /** 程序统计 + 可选 AI 归纳；AI 不可用时只输出规则结果。 */
    public ChainReport analyze(String skillContent, ObservationMetrics metrics) {
        ChainReport report = new ChainReport();
        Map<String, Object> stats = buildStats(metrics);
        report.setStats(stats);

        Map<String, Map<String, Object>> aiFindings = aiFindings(skillContent, stats);

        int weighted = 0;
        int weightSum = 0;
        boolean anyAi = false;
        for (String[] dim : DIMENSIONS) {
            DimResult result = new DimResult();
            result.setKey(dim[0]);
            result.setName(dim[1]);
            int weight = Integer.parseInt(dim[2]);
            result.setWeight(weight);
            result.setDescription(dim.length > 3 ? dim[3] : "");
            result.setScore(scoreDim(dim[0], stats));
            result.setSource("rule");
            Map<String, Object> ai = resolveAi(aiFindings, dim[0], dim[1]);
            if (ai != null) {
                Object aiScore = ai.get("score");
                if (aiScore instanceof Number) {
                    result.setScore(clampScore(((Number) aiScore).intValue()));
                }
                result.setSource("ai");
                anyAi = true;
            }
            applyFindings(result, programFindings(dim[0], stats), "rule");
            if (ai != null) {
                applyFindings(result, aiFindingsOf(ai), "ai");
            }
            weighted += result.getScore() * weight;
            weightSum += weight;
            report.getDimensions().add(result);
            for (Map<String, Object> row : result.getIssueRows()) {
                report.getIssueRows().add(row);
            }
        }
        report.setScore(weightSum <= 0 ? 0 : (int) Math.round((double) weighted / weightSum));
        report.setSource(anyAi ? "ai+rule" : "rule");
        sortBySeverity(report.getIssueRows());
        return report;
    }

    // ------------------------------------------------------------- stats

    private Map<String, Object> buildStats(ObservationMetrics metrics) {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        boolean available = metrics != null && metrics.isAvailable();
        stats.put("sampleCount", Integer.valueOf(available ? metrics.getCalls() : 0));
        stats.put("skillTurns", Integer.valueOf(available ? metrics.getSkillTurns() : 0));
        stats.put("errors", Integer.valueOf(available ? metrics.getErrors() : 0));
        stats.put("reloadTurns", Integer.valueOf(available ? metrics.getReloadTurns() : 0));
        stats.put("avgTokens", Long.valueOf(available ? metrics.getAvgTokens() : 0L));
        double coverage = !available || metrics.getChecklistCoverage() < 0d
                ? -1d : round2(metrics.getChecklistCoverage());
        stats.put("coverage", Double.valueOf(coverage));
        stats.put("failureRate", Double.valueOf(available ? round2(metrics.getErrorRate()) : 0d));
        stats.put("reloadRate", Double.valueOf(available ? round2(metrics.getReloadRate()) : 0d));
        return stats;
    }

    private int scoreDim(String key, Map<String, Object> stats) {
        double coverage = num(stats.get("coverage"), -1d);
        double failureRate = num(stats.get("failureRate"), 0d);
        double reloadRate = num(stats.get("reloadRate"), 0d);
        long avgTokens = (long) num(stats.get("avgTokens"), 0d);
        int n = (int) num(stats.get("sampleCount"), 0d);
        if (n <= 0) {
            return 50;
        }
        if (DIM_STEP.equals(key)) {
            if (coverage < 0) {
                return 55;
            }
            return clampScore((int) Math.round(coverage * 100d));
        }
        if (DIM_EXEC.equals(key) || DIM_FAIL.equals(key)) {
            return clampScore((int) Math.round(100d * (1d - failureRate)));
        }
        if (DIM_EFF.equals(key)) {
            int score = 100;
            if (avgTokens > 30000L) score -= 25;
            else if (avgTokens > 15000L) score -= 12;
            if (reloadRate > 0.3d) score -= 25;
            else if (reloadRate > 0.1d) score -= 12;
            return clampScore(score);
        }
        if (DIM_LOAD.equals(key)) {
            return clampScore((int) Math.round(100d * (1d - reloadRate)));
        }
        // align：有样本但覆盖差时偏低
        if (coverage < 0) return 60;
        return clampScore((int) Math.round(70d + coverage * 30d));
    }

    // ------------------------------------------------------------- findings

    /** 规则可判定的问题：[问题, 修复意见, high|normal]。 */
    private List<String[]> programFindings(String dimKey, Map<String, Object> stats) {
        List<String[]> out = new ArrayList<String[]>();
        double coverage = num(stats.get("coverage"), -1d);
        double failureRate = num(stats.get("failureRate"), 0d);
        double reloadRate = num(stats.get("reloadRate"), 0d);
        int n = (int) num(stats.get("sampleCount"), 0d);
        if (n == 0) {
            out.add(new String[] {
                    "该版本暂无会话链路数据，无法评价使用效果",
                    "采集或上传该版本的使用记录后再刷新链路分析",
                    "normal"
            });
            return out;
        }
        if (DIM_LOAD.equals(dimKey)) {
            if (reloadRate > 0.2d) {
                out.add(new String[] {
                        String.format("重复载入 %.0f%%（%d 次调用中同一轮反复读取技能）",
                                reloadRate * 100d, n),
                        "把触发条件写清楚，避免一轮内重复载入",
                        reloadRate > 0.4d ? "high" : "normal"
                });
            } else {
                out.add(new String[] {
                        String.format("基于 %d 次调用：触发与载入正常（出错 %.0f%%）",
                                n, failureRate * 100d),
                        "核对触发词/场景是否写清，避免误触发或该用时用不上",
                        "normal"
                });
            }
        }
        if (DIM_STEP.equals(dimKey)) {
            if (coverage < 0) {
                out.add(new String[] {
                        String.format("%d 次调用缺少可比对的预期步骤，无法确认是否按说明执行", n),
                        "把 SKILL.md 步骤写成有序、可核对的 1/2/3 条目",
                        "high"
                });
            } else if (coverage < 0.8d) {
                out.add(new String[] {
                        String.format("步骤完成度仅 %.0f%%：预期步骤命中不足", coverage * 100d),
                        "按失败最多的步骤改写说明；删掉用不到或做不到的步骤",
                        coverage < 0.5d ? "high" : "normal"
                });
            } else {
                out.add(new String[] {
                        String.format("步骤完成度 %.0f%%，仍有未命中步骤", coverage * 100d),
                        "对照未命中步骤补齐动作描述与输出约定",
                        "normal"
                });
            }
        }
        if (DIM_EXEC.equals(dimKey)) {
            if (failureRate > 0.05d) {
                out.add(new String[] {
                        String.format("出错占比 %.0f%%：执行出错偏多", failureRate * 100d),
                        "核对命令、脚本路径、参数示例是否与真实环境一致",
                        failureRate > 0.15d ? "high" : "normal"
                });
            } else {
                out.add(new String[] {
                        String.format("出错占比 %.0f%%，需抽查工具/命令用法说明是否可照做",
                                failureRate * 100d),
                        "为关键命令补全完整调用示例（含参数）",
                        "normal"
                });
            }
        }
        if (DIM_FAIL.equals(dimKey)) {
            if (failureRate > 0.02d) {
                out.add(new String[] {
                        String.format("存在出错调用（%.0f%%），需确认失败时是否有可执行指引",
                                failureRate * 100d),
                        "补充失败分支：重试次数、停止条件、如何确认已恢复",
                        failureRate > 0.1d ? "high" : "normal"
                });
            } else {
                out.add(new String[] {
                        String.format("%d 次调用出错很少，但说明中失败处理是否完整需人工核对", n),
                        "检查是否写明「若 X 失败则 Y」，避免只写成功路径",
                        "normal"
                });
            }
        }
        if (DIM_ALIGN.equals(dimKey)) {
            if (coverage >= 0 && coverage < 0.7d) {
                out.add(new String[] {
                        String.format("说明与实际行为偏差：完成度 %.0f%%", coverage * 100d),
                        "对照实际会话修正说明，删除与行为不符的描述",
                        "normal"
                });
            } else {
                out.add(new String[] {
                        "按说明执行的一致性基本可接受，建议抽查典型会话",
                        "保持说明与行为同步，行为变化时更新说明",
                        "normal"
                });
            }
        }
        if (DIM_EFF.equals(dimKey)) {
            long avgTokens = (long) num(stats.get("avgTokens"), 0d);
            if (avgTokens > 15000L || reloadRate > 0.2d) {
                out.add(new String[] {
                        String.format("调用成本偏高：平均 %d tokens，重复载入 %.0f%%",
                                avgTokens, reloadRate * 100d),
                        "合并重复说明，删除用不到的步骤以降低成本",
                        "normal"
                });
            } else {
                out.add(new String[] {
                        String.format("调用效率正常（平均 %d tokens）", avgTokens),
                        "保持当前步骤规模，新增步骤前先确认必要性",
                        "normal"
                });
            }
        }
        return out;
    }

    private void applyFindings(DimResult result, List<String[]> findings, String source) {
        for (String[] finding : findings) {
            String problem = finding[0];
            String fix = finding[1];
            String severity = finding.length > 2 ? finding[2] : "normal";
            if (problem == null || problem.isEmpty()) continue;
            result.getProblems().add(problem);
            result.getFixes().add(fix);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("severity", severity);
            row.put("problem", problem);
            row.put("fix", fix);
            row.put("dimension", result.getName());
            row.put("deduction", Integer.valueOf(deductionFor(severity)));
            row.put("score", row.get("deduction"));
            row.put("source", source);
            result.getIssueRows().add(row);
        }
    }

    private static int deductionFor(String severity) {
        if ("high".equals(severity)) return 20;
        if ("low".equals(severity)) return 5;
        return 10;
    }

    // ------------------------------------------------------------- AI path

    private static final String CHAIN_SYSTEM_PROMPT =
            "你是技能链路分析评审。结合技能正文与该版本的使用统计，对六个链路维度打 0-100 分。"
                    + "只输出 JSON：{\"dims\":[{\"key\":\"load_trigger\",\"score\":80,"
                    + "\"problems\":[\"问题\"],\"fixes\":[\"改法\"]}...]}；"
                    + "key 只能是 load_trigger / step_completion / execution_correctness / "
                    + "failure_handling / text_behavior_alignment / efficiency。";

    /** AI 归纳；未启用 / 预算受限 / 解析失败时返回空 map（规则结果保留）。 */
    private Map<String, Map<String, Object>> aiFindings(String skillContent, Map<String, Object> stats) {
        boolean llmAllowed = configService == null || configService.chainEnabled();
        if (!llmAllowed || modelClient == null) {
            return Collections.emptyMap();
        }
        try {
            Optional<String> raw = modelClient.chat("skill-quality-chain", CHAIN_SYSTEM_PROMPT,
                    "使用统计：" + toJson(stats) + "\n技能正文：\n" + (skillContent == null ? "" : skillContent));
            if (!raw.isPresent()) {
                return Collections.emptyMap();
            }
            return parseAiDims(raw.get());
        } catch (RuntimeException ex) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseAiDims(String rawText) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        if (rawText == null) return out;
        String trimmed = rawText.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) return out;
        Map<String, Object> root;
        try {
            root = mapper.readValue(trimmed.substring(start, end + 1), Map.class);
        } catch (Exception ex) {
            return out;
        }
        Object dimsNode = root.get("dims");
        if (!(dimsNode instanceof List)) return out;
        for (Object item : (List<Object>) dimsNode) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> node = (Map<String, Object>) item;
            String key = firstText(node.get("key"), node.get("name"));
            if (key == null) continue;
            out.put(key, node);
        }
        return out;
    }

    /** Match an AI node by dimension key, falling back to the display name. */
    private static Map<String, Object> resolveAi(Map<String, Map<String, Object>> aiFindings,
                                                 String key, String name) {
        Map<String, Object> node = aiFindings.get(key);
        if (node != null) return node;
        return aiFindings.get(name);
    }

    @SuppressWarnings("unchecked")
    private List<String[]> aiFindingsOf(Map<String, Object> node) {
        List<String[]> out = new ArrayList<String[]>();
        Object problems = node.get("problems");
        Object fixes = node.get("fixes");
        List<String> problemList = toStringList(problems);
        List<String> fixList = toStringList(fixes);
        for (int i = 0; i < problemList.size(); i++) {
            String problem = problemList.get(i);
            String fix = i < fixList.size() ? fixList.get(i) : "按评审意见修正技能说明";
            out.add(new String[] {problem, fix, "normal"});
        }
        return out;
    }

    private static List<String> toStringList(Object value) {
        List<String> out = new ArrayList<String>();
        if (!(value instanceof List)) return out;
        for (Object item : (List<?>) value) {
            if (item != null) out.add(String.valueOf(item));
        }
        return out;
    }

    private static String firstText(Object first, Object second) {
        if (first != null && !String.valueOf(first).trim().isEmpty()) {
            return String.valueOf(first).trim();
        }
        if (second != null && !String.valueOf(second).trim().isEmpty()) {
            return String.valueOf(second).trim();
        }
        return null;
    }

    // ------------------------------------------------------------- helpers

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static void sortBySeverity(List<Map<String, Object>> rows) {
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Integer.valueOf(severityRank(String.valueOf(a.get("severity"))))
                        .compareTo(Integer.valueOf(severityRank(String.valueOf(b.get("severity")))));
            }
        });
    }

    private static int severityRank(String severity) {
        return "high".equals(severity) ? 0 : ("normal".equals(severity) ? 1 : 2);
    }

    private static double num(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static int clampScore(int score) {
        if (score < 0) return 0;
        if (score > 100) return 100;
        return score;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
