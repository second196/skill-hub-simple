package com.km.skillhub.skillhubquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.observation.ObservationRepository;
import com.km.skillhub.skill.SkillApiException;
import com.km.skillhub.skill.SkillRepository;
import com.km.skillhub.skill.SkillVersions;
import com.km.skillhub.skillhubquality.SkillEffectAnalyzer.EffectReport;
import com.km.skillhub.skillhubquality.SkillEffectAnalyzer.ObservationMetrics;
import com.km.skillhub.skillhubquality.SkillQualityScorer.QualityReport;
import com.km.skillhub.skillhubquality.SkillStaticChecker.Issue;
import com.km.skillhub.skillhubquality.SkillStaticChecker.SkillFile;
import com.km.skillhub.skillhubquality.SkillStaticChecker.StaticReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 质量报告编排：SkillStaticChecker(L1) + SkillQualityScorer(L2) +
 * SkillEffectAnalyzer/SkillChainAnalyzer(L3) + SkillOverallEvaluator。
 *
 * <p>报告组装为前端契约（skillQuality.ts）；LLM 不可用时写法评分降级为
 * unavailable，链路分析保持规则结果；无观测数据时 effect 走降级结构
 * （score=null、sampleInsufficient=true），结论被 sampleOk 压到 WATCH 以内。
 * 落库 rule_version 取 skill_quality_config，权重/阈值每次评估读最新配置。</p>
 */
@Service
public class SkillQualityServiceImpl implements SkillQualityService {

    private static final Logger log = LoggerFactory.getLogger(SkillQualityServiceImpl.class);

    /** 前端契约 conclusion 口径。 */
    static final String CONCLUSION_NEED_OPTIMIZE = "need_optimize";
    static final String CONCLUSION_WATCH = "watch";
    static final String CONCLUSION_KEEP = "keep";
    static final String CONCLUSION_NO_DATA = "no_data";

    static final String STATUS_OK = "ok";

    /** 格式检查五维：code, 名称, 权重, 说明 */
    private static final String[][] FILE_METRICS = {
            {SkillStaticChecker.METRIC_A, "名称与说明", "15",
                    "Frontmatter 中 name 与 description 是否完整规范，能否清晰界定能力边界与使用时机"},
            {SkillStaticChecker.METRIC_B, "引用文件", "25",
                    "正文引用的 scripts、references、assets 路径是否真实存在且可访问"},
            {SkillStaticChecker.METRIC_C, "步骤结构", "20",
                    "是否存在有序、可执行的工作流步骤定义"},
            {SkillStaticChecker.METRIC_D, "失败与反例", "20",
                    "是否包含失败分支、回退策略及禁止事项说明"},
            {SkillStaticChecker.METRIC_E, "可执行表述", "20",
                    "是否避免模糊软化措辞，且未绑定单一运行环境"}
    };

    /**
     * skill内容检查八维（darwin 口径，去掉「实测表现」——效果归 skill链路分析）。
     * 名称, 权重, 评分标准。LLM 仍按 9 维输出时跳过 index=7。
     */
    private static final String[][] WORDING_DIMS = {
            {"Frontmatter质量", "7",
                    "name 规范；description 含做什么、何时用与触发词，长度不超上限，禁止空话结尾"},
            {"工作流清晰度", "12",
                    "步骤明确可执行、有序号，每步具有明确输入与输出"},
            {"失败模式编码", "12",
                    "显式编码失败分支（若 X 失败则 Y）、fallback 与错误恢复；仅写正向流程扣分"},
            {"检查点设计", "6",
                    "高风险或不可逆操作前设置显性确认点；仅「建议考虑」类措辞不计"},
            {"可执行具体性", "18",
                    "具备具体参数、格式与示例，可直接执行；禁止「建议/根据情况/灵活把握」等软化措辞"},
            {"资源整合度", "4",
                    "references、scripts、assets 引用正确且路径可达"},
            {"整体架构", "12",
                    "章节层次清晰，无冗余、遗漏与自相矛盾"},
            {"反例与黑名单", "6",
                    "明确「不要做什么」反例、禁止操作与危险动作清单"}
    };

    /** LLM 九维下标 → WORDING_DIMS 下标（跳过 index 7「实测表现」）。 */
    private static final int[] WORDING_DIM_MAP = {0, 1, 2, 3, 4, 5, 6, -1, 7};

    private static final String[] WORDING_NAMES = {
            "Frontmatter质量", "工作流清晰度", "失败模式编码", "检查点设计", "可执行具体性",
            "资源整合度", "整体架构", "反例与黑名单"
    };

    private final SkillRepository skillRepository;
    private final SkillQualityReportRepository reportRepository;
    private final ObservationRepository observationRepository;
    private final SkillStaticChecker staticChecker;
    private final SkillQualityScorer qualityScorer;
    private final SkillEffectAnalyzer effectAnalyzer;
    private final SkillChainAnalyzer chainAnalyzer;
    private final SkillOverallEvaluator overallEvaluator;
    private final SkillQualityConfigService configService;
    /** 报告 JSON 专用（默认包含 null，保持契约字段完整；不受 jackson non_null 全局配置影响）。 */
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public SkillQualityServiceImpl(SkillRepository skillRepository,
                                   SkillQualityReportRepository reportRepository,
                                   ObservationRepository observationRepository,
                                   SkillStaticChecker staticChecker,
                                   SkillQualityScorer qualityScorer,
                                   SkillEffectAnalyzer effectAnalyzer,
                                   SkillChainAnalyzer chainAnalyzer,
                                   SkillOverallEvaluator overallEvaluator,
                                   SkillQualityConfigService configService) {
        this.skillRepository = skillRepository;
        this.reportRepository = reportRepository;
        this.observationRepository = observationRepository;
        this.staticChecker = staticChecker;
        this.qualityScorer = qualityScorer;
        this.effectAnalyzer = effectAnalyzer;
        this.chainAnalyzer = chainAnalyzer;
        this.overallEvaluator = overallEvaluator;
        this.configService = configService;
    }

    // ------------------------------------------------------------- read

    @Override
    public Map<String, Object> qualityReport(String slug, String version) {
        SkillRefs refs = resolve(slug, version);
        Map<String, Object> stored = reportRepository.latest(refs.skillId, refs.versionId);
        if (stored != null) {
            return fromStored(stored, refs);
        }
        return analyzeChain(refs);
    }

    @Override
    public List<Map<String, Object>> qualitySummary(String slug) {
        if (slug == null || slug.trim().isEmpty()) {
            throw new SkillApiException("slug 不能为空", SkillApiException.CODE_VALIDATION_ERROR);
        }
        Map<String, Object> detail = skillRepository.detail(slug.trim());
        Object skillId = detail.get("id");
        List<Map<String, Object>> rows = reportRepository.summaryRows(
                skillId instanceof Number ? Long.valueOf(((Number) skillId).longValue()) : null);
        List<Map<String, Object>> summaries = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("version", row.get("version"));
            summary.put("analyzedAt", isoOf(row.get("analyzed_at")));
            summary.put("overallScore", row.get("overall_score"));
            summary.put("fileScore", row.get("file_score"));
            summary.put("wordingScore", row.get("wording_score"));
            summary.put("effectScore", row.get("effect_score"));
            summaries.add(summary);
        }
        return summaries;
    }

    // ------------------------------------------------------------- analyze

    @Override
    public void analyzeStaticOnly(String slug, String version) {
        SkillRefs refs = resolve(slug, version);
        List<SkillFile> files = loadFiles(refs.versionId);
        StaticReport l1 = staticChecker.check(files);
        String content = skillContent(files);
        ObservationMetrics metrics = loadMetrics(refs);
        QualityReport l2 = qualityScorer.score(content, metrics);

        Map<String, Object> effect;
        Map<String, Object> stored = reportRepository.latest(refs.skillId, refs.versionId);
        Map<String, Object> storedEffect = stored == null
                ? null : parseSection(stringOf(stored.get("l3_json")));
        if (isUsableSection(storedEffect)) {
            ensureEffectShape(storedEffect);
            effect = storedEffect;
        } else {
            effect = buildEffectSection(refs, content, metrics);
        }

        Map<String, Object> fileCheck = ensureFileCheckDimensions(fileCheckSection(l1));
        Map<String, Object> wording = ensureWordingDimensions(wordingSection(l2));
        ensureIssueDeductions(fileCheck);
        ensureIssueDeductions(wording);
        ensureIssueDeductions(effect);

        SkillOverallEvaluator.OverallReport overall = overallFromSections(fileCheck, wording, effect);
        persist(refs, fileCheck, wording, effect, overall, "static-backfill");
    }

    @Override
    public Map<String, Object> analyzeChainForVersion(String slug, String version) {
        return analyzeChain(resolve(slug, version));
    }

    @Override
    public int refreshAllChainAnalyses() {
        List<Map<String, Object>> rows;
        try {
            rows = observationRepository.listObservedSkills();
        } catch (RuntimeException ex) {
            log.warn("skill chain analysis refresh skipped: {}", ex.getMessage());
            return 0;
        }
        int count = 0;
        if (rows == null) return 0;
        for (Map<String, Object> row : rows) {
            Object slugValue = row.get("slug");
            if (slugValue == null) continue;
            try {
                analyzeChainForVersion(String.valueOf(slugValue), null);
                count++;
            } catch (RuntimeException ex) {
                // 单个失败不阻断批任务
            }
        }
        return count;
    }

    /** 链路分析主流程（已解析 refs 版本）。 */
    private Map<String, Object> analyzeChain(SkillRefs refs) {
        List<SkillFile> files = loadFiles(refs.versionId);
        String content = skillContent(files);
        ObservationMetrics metrics = loadMetrics(refs);
        Map<String, Object> stored = reportRepository.latest(refs.skillId, refs.versionId);

        // L1/L2：有库内结果则原样沿用；没有才首次计算（读接口触发分析时兜底）
        Map<String, Object> storedFileCheck = stored == null
                ? null : parseSection(stringOf(stored.get("l1_json")));
        Map<String, Object> storedWording = stored == null
                ? null : parseSection(stringOf(stored.get("l2_json")));
        Map<String, Object> fileCheck;
        Map<String, Object> wording;
        if (isUsableSection(storedFileCheck) && isUsableSection(storedWording)) {
            fileCheck = storedFileCheck;
            wording = storedWording;
        } else {
            StaticReport l1 = staticChecker.check(files);
            QualityReport l2 = qualityScorer.score(content, metrics);
            fileCheck = fileCheckSection(l1);
            wording = wordingSection(l2);
        }
        fileCheck = ensureFileCheckDimensions(fileCheck);
        wording = ensureWordingDimensions(wording);
        ensureIssueDeductions(fileCheck);
        ensureIssueDeductions(wording);

        Map<String, Object> effect = buildEffectSection(refs, content, metrics);
        ensureIssueDeductions(effect);

        SkillOverallEvaluator.OverallReport overall = overallFromSections(fileCheck, wording, effect);
        persist(refs, fileCheck, wording, effect, overall, chainSourceOf(effect));
        return reportMap(refs, overall, fileCheck, wording, effect, Instant.now());
    }

    /** L3 使用效果 + 六维链路分析（观测数据缺失时返回降级结构）。 */
    private Map<String, Object> buildEffectSection(SkillRefs refs, String content,
                                                   ObservationMetrics metrics) {
        EffectReport l3 = effectAnalyzer.analyze(metrics);
        com.km.skillhub.skillhubquality.SkillChainAnalyzer.ChainReport chain =
                l3 == null || !metrics.isAvailable()
                        ? null : chainAnalyzer.analyze(content, metrics);
        return effectSection(l3, chain, metrics);
    }

    private String chainSourceOf(Map<String, Object> effect) {
        Object source = effect == null ? null : effect.get("chainSource");
        return source == null ? "rule" : String.valueOf(source);
    }

    // ------------------------------------------------------------- sections

    private Map<String, Object> fileCheckSection(StaticReport l1) {
        Map<String, Object> section = new LinkedHashMap<String, Object>();
        if (l1 == null) {
            section.put("score", Integer.valueOf(0));
            section.put("status", "fail");
            section.put("errorCount", Integer.valueOf(0));
            section.put("warningCount", Integer.valueOf(0));
            section.put("items", Collections.emptyList());
            section.put("problems", Collections.singletonList("无法读取技能文件"));
            section.put("fixes", Collections.singletonList("确认技能包内包含 SKILL.md 后重新分析"));
            section.put("suggestions", Collections.emptyList());
            return section;
        }
        int errorCount = intOf(l1.getMetrics().get("errorCount"));
        int warnCount = intOf(l1.getMetrics().get("warnCount"));
        section.put("score", Integer.valueOf(l1.getScore()));
        section.put("status", errorCount > 0 ? "fail" : (warnCount > 0 ? "pass_with_warning" : "pass"));
        section.put("errorCount", Integer.valueOf(errorCount));
        section.put("warningCount", Integer.valueOf(warnCount));

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> dims = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> suggestions = new ArrayList<Map<String, Object>>();
        List<String> problems = new ArrayList<String>();
        List<String> fixes = new ArrayList<String>();
        List<Map<String, Object>> issueRows = new ArrayList<Map<String, Object>>();
        for (String[] metric : FILE_METRICS) {
            String code = metric[0];
            String name = metric[1];
            int weight = Integer.parseInt(metric[2]);
            String description = metric[3];
            Issue worst = worstIssue(l1.getIssues(), code);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", name);
            Map<String, Object> dim = new LinkedHashMap<String, Object>();
            dim.put("key", code);
            dim.put("name", name);
            dim.put("weight", Integer.valueOf(weight));
            dim.put("description", description);
            if (worst == null) {
                item.put("status", "pass");
                item.put("detail", "通过");
                dim.put("score", Integer.valueOf(100));
            } else {
                boolean failed = SkillStaticChecker.LEVEL_ERROR.equals(worst.getLevel());
                item.put("status", failed ? "fail" : "warn");
                item.put("detail", worst.getMessage());
                if (worst.getSuggestion() != null && !worst.getSuggestion().isEmpty()) {
                    item.put("suggestion", worst.getSuggestion());
                }
                dim.put("score", Integer.valueOf(failed ? 20 : 55));
                String problemLine = worst.getMessage();
                if (worst.getLocation() != null && !worst.getLocation().isEmpty()) {
                    problemLine = problemLine + "（" + worst.getLocation() + "）";
                }
                problems.add(problemLine);
                String fixLine = worst.getSuggestion() == null || worst.getSuggestion().isEmpty()
                        ? "按检查项补齐或改写「" + name + "」相关内容"
                        : worst.getSuggestion();
                fixes.add(fixLine);
                issueRows.add(issueRow(failed ? "high" : "normal",
                        problemLine, fixLine, name,
                        Integer.valueOf(l1Deduction(worst.getLevel()))));
                suggestions.add(suggestion(worst.getMessage(),
                        worst.getLocation() == null ? name : worst.getLocation(),
                        fixLine, "file"));
            }
            items.add(item);
            dims.add(dim);
        }
        section.put("items", items);
        applyDimensionAggregates(dims, issueRows);
        section.put("dimensions", dims);
        section.put("score", Integer.valueOf(weightedSectionScore(dims)));
        section.put("problems", problems);
        section.put("fixes", fixes);
        section.put("issueRows", sortBySeverity(issueRows));
        section.put("suggestions", suggestions);
        return section;
    }

    private Map<String, Object> wordingSection(QualityReport l2) {
        Map<String, Object> section = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> dims = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> suggestions = new ArrayList<Map<String, Object>>();
        if (l2 == null) {
            section.put("score", Integer.valueOf(0));
            section.put("topGap", "暂无写法评分");
            section.put("dimensions", dims);
            section.put("problems", Collections.singletonList("暂无内容检查结果"));
            section.put("fixes", Collections.singletonList("配置 AI 模型后重新分析，或检查技能正文是否为空"));
            section.put("suggestions", suggestions);
            return section;
        }
        boolean unavailable = SkillQualityScorer.EVAL_MODE_UNAVAILABLE.equals(l2.getEvalMode());
        if (unavailable) {
            section.put("unavailable", Boolean.TRUE);
        }
        List<SkillQualityScorer.Dimension> source = l2.getDims();
        for (int i = 0; i < WORDING_DIMS.length; i++) {
            SkillQualityScorer.Dimension dim = null;
            if (source != null) {
                for (int src = 0; src < source.size(); src++) {
                    if (src < WORDING_DIM_MAP.length && WORDING_DIM_MAP[src] == i) {
                        dim = source.get(src);
                        break;
                    }
                }
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("key", "w" + (i + 1));
            row.put("name", WORDING_DIMS[i][0]);
            row.put("weight", Integer.valueOf(WORDING_DIMS[i][1]));
            row.put("description", WORDING_DIMS[i][2]);
            // 维度原始参考分（LLM 1–10 → 0–100）；汇总得分在 applyDimensionAggregates 覆盖
            row.put("score", Integer.valueOf(dim == null ? 0 : dim.getScore() * 10));
            if (dim != null && dim.getReason() != null && !dim.getReason().isEmpty()) {
                row.put("gapNote", dim.getReason());
            }
            dims.add(row);
        }
        String topGap = "暂无短板";
        List<SkillQualityScorer.Dimension> shortboard = l2.getShortboard();
        if (shortboard != null && !shortboard.isEmpty()) {
            SkillQualityScorer.Dimension top = shortboard.get(0);
            int index = indexOfDim(source, top);
            String label = index >= 0 && index < WORDING_NAMES.length
                    ? WORDING_NAMES[index] : top.getName();
            topGap = label + (top.getReason() == null || top.getReason().isEmpty()
                    ? "" : "（" + top.getReason() + "）");
        }
        section.put("score", Integer.valueOf((int) Math.round(l2.getTotal())));
        section.put("topGap", topGap);
        section.put("dimensions", dims);

        List<String> problems = new ArrayList<String>();
        List<String> fixes = new ArrayList<String>();
        List<Map<String, Object>> issueRows = new ArrayList<Map<String, Object>>();
        if (!unavailable && shortboard != null) {
            for (SkillQualityScorer.Dimension dim : shortboard) {
                int srcIndex = indexOfDim(source, dim);
                int mapped = (srcIndex >= 0 && srcIndex < WORDING_DIM_MAP.length)
                        ? WORDING_DIM_MAP[srcIndex] : -1;
                if (mapped < 0) {
                    continue; // 跳过「实测表现」，效果归 skill链路分析
                }
                String label = mapped < WORDING_DIMS.length ? WORDING_DIMS[mapped][0] : dim.getName();
                String reason = dim.getReason() == null ? "" : dim.getReason().trim();
                String dimSuggestion = dim.getSuggestion() == null ? "" : dim.getSuggestion().trim();
                if (reason.isEmpty()) {
                    continue;
                }
                String fix = dimSuggestion.isEmpty()
                        ? defaultWordingFix(mapped, label) : dimSuggestion;
                problems.add(reason);
                fixes.add(fix);
                String severity = dim.getScore() <= 5 ? "high" : "normal";
                int weight = Integer.parseInt(WORDING_DIMS[mapped][1]);
                issueRows.add(issueRow(severity, reason, fix, label,
                        Integer.valueOf(l2Deduction(severity, weight))));
                suggestions.add(suggestion(reason, label, fix, "wording"));
            }
        }
        if (problems.isEmpty()) {
            if (unavailable) {
                problems.add("写法评分不可用（未配置 AI 模型或调用受限）");
                fixes.add("配置 AI 模型后重新分析");
            } else {
                problems.add("未发现明确的内容问题");
                fixes.add("保持当前写法；如有使用问题见 skill链路分析");
            }
        }
        section.put("problems", problems);
        section.put("fixes", fixes);
        section.put("issueRows", issueRows);
        section.put("suggestions", suggestions);
        return section;
    }

    /**
     * L3 使用效果段。l3==null（无观测数据）走 kmplm 一致的降级结构；
     * 有数据时按指标产出问题行，并合并六维链路分析结果。
     */
    private Map<String, Object> effectSection(EffectReport l3,
                                              com.km.skillhub.skillhubquality.SkillChainAnalyzer.ChainReport chain,
                                              ObservationMetrics metrics) {
        Map<String, Object> section = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> issues = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> suggestions = new ArrayList<Map<String, Object>>();
        if (l3 == null) {
            section.put("score", null);
            section.put("sampleInsufficient", Boolean.TRUE);
            section.put("completionRate", null);
            section.put("errorRate", null);
            section.put("cost", "unknown");
            section.put("commonIssues", issues);
            section.put("problems", Collections.singletonList("暂无链路数据"));
            section.put("fixes", Collections.singletonList("上传观测数据后重新分析"));
            section.put("issueRows", Collections.emptyList());
            section.put("dimensions", Collections.emptyList());
            section.put("suggestions", suggestions);
            return section;
        }

        section.put("score", l3.isSampleOk() ? Integer.valueOf(l3.getScore()) : null);
        if (!l3.isSampleOk()) {
            section.put("sampleInsufficient", Boolean.TRUE);
        }
        section.put("sampleCount", Integer.valueOf(l3.getSampleCount()));
        section.put("completionRate", l3.getCompletionRate());
        section.put("errorRate", Double.valueOf(round2(l3.getErrorRate())));
        section.put("cost", mapCost(l3.getCostLevel()));

        List<String> problems = new ArrayList<String>();
        List<String> fixes = new ArrayList<String>();
        List<Map<String, Object>> issueRows = new ArrayList<Map<String, Object>>();

        if (metrics != null && metrics.isAvailable() && metrics.getErrorRate() > 0.05d
                && metrics.getErrors() > 0) {
            String problemLine = "使用中出现「执行出错」，共 " + metrics.getErrors() + " 次（出错占比 "
                    + Math.round(metrics.getErrorRate() * 100) + "%）";
            String fixLine = "核对命令、脚本路径、参数示例是否与真实环境一致";
            Map<String, Object> issue = new LinkedHashMap<String, Object>();
            issue.put("title", "执行出错");
            issue.put("count", Integer.valueOf(metrics.getErrors()));
            issue.put("category", "skill");
            issues.add(issue);
            problems.add(problemLine);
            fixes.add(fixLine);
            String severity = metrics.getErrorRate() > 0.15d ? "high" : "normal";
            issueRows.add(issueRow(severity, problemLine, fixLine, "执行正确性",
                    Integer.valueOf(l3Deduction(metrics.getErrors(), severity))));
            suggestions.add(suggestion(problemLine, fixLine, fixLine, "effect"));
        }
        if (metrics != null && metrics.isAvailable() && metrics.getReloadRate() > 0.2d
                && metrics.getReloadTurns() > 0) {
            String problemLine = "同一轮重复载入技能 " + metrics.getReloadTurns() + " 次（重复载入率 "
                    + Math.round(metrics.getReloadRate() * 100) + "%）";
            String fixLine = "把触发条件写清楚，避免一轮内重复载入";
            Map<String, Object> issue = new LinkedHashMap<String, Object>();
            issue.put("title", "重复载入");
            issue.put("count", Integer.valueOf(metrics.getReloadTurns()));
            issue.put("category", "usage");
            issues.add(issue);
            problems.add(problemLine);
            fixes.add("不计入技能修改建议");
            String severity = metrics.getReloadRate() > 0.4d ? "high" : "normal";
            issueRows.add(issueRow(severity, problemLine, fixLine, "载入与触发",
                    Integer.valueOf(l3Deduction(metrics.getReloadTurns(), severity))));
        }
        if ("high".equals(l3.getCostLevel())) {
            String problemLine = "一次使用步骤或消耗偏高（平均 "
                    + (metrics == null ? 0 : metrics.getAvgTokens()) + " tokens）";
            String costFix = "合并重复说明，删除用不到的步骤以降低成本";
            problems.add(problemLine);
            fixes.add(costFix);
            issueRows.add(issueRow("normal", problemLine, costFix, "调用效率"));
            suggestions.add(suggestion(problemLine, costFix, costFix, "effect"));
        }

        if (chain != null) {
            List<Map<String, Object>> dims = new ArrayList<Map<String, Object>>();
            for (com.km.skillhub.skillhubquality.SkillChainAnalyzer.DimResult dim
                    : chain.getDimensions()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("key", dim.getKey());
                row.put("name", dim.getName());
                row.put("weight", Integer.valueOf(dim.getWeight()));
                row.put("description", dim.getDescription());
                row.put("score", Integer.valueOf(dim.getScore()));
                row.put("problems", dim.getProblems());
                row.put("fixes", dim.getFixes());
                row.put("source", dim.getSource());
                dims.add(row);
                for (Map<String, Object> chainIssue : dim.getIssueRows()) {
                    issueRows.add(chainIssue);
                    String p = String.valueOf(chainIssue.get("problem"));
                    String f = String.valueOf(chainIssue.get("fix"));
                    if (!problems.contains(p)) {
                        problems.add(p);
                        fixes.add(f);
                    }
                }
            }
            section.put("chainScore", Integer.valueOf(chain.getScore()));
            section.put("chainStats", chain.getStats());
            section.put("chainSource", chain.getSource());
            section.put("dimensions", dims);
            applyDimensionAggregates(dims, issueRows);
            if (!dims.isEmpty()) {
                section.put("score", Integer.valueOf(weightedSectionScore(dims)));
            }
        }

        if (problems.isEmpty()) {
            problems.add("暂无链路问题");
            fixes.add("保持当前使用方式");
        }
        section.put("commonIssues", issues);
        section.put("problems", problems);
        section.put("fixes", fixes);
        section.put("issueRows", sortBySeverity(issueRows));
        section.put("suggestions", suggestions);
        return section;
    }

    // ------------------------------------------------------------- overall

    /** 从三块契约 Map 的 score 计算综合分（与页面一致，权重读配置）。 */
    private SkillOverallEvaluator.OverallReport overallFromSections(
            Map<String, Object> fileCheck, Map<String, Object> wording, Map<String, Object> effect) {
        Integer l1 = sectionScore(fileCheck);
        Integer l2 = sectionScore(wording);
        Integer l3 = sectionScore(effect);
        boolean l1HasError = fileCheck != null
                && ("fail".equals(String.valueOf(fileCheck.get("status")))
                    || (fileCheck.get("errorCount") instanceof Number
                        && ((Number) fileCheck.get("errorCount")).intValue() > 0));
        boolean l2Available = l2 != null
                && wording != null
                && !Boolean.TRUE.equals(wording.get("unavailable"));
        boolean sampleOk = effect == null || !Boolean.TRUE.equals(effect.get("sampleInsufficient"));
        return overallEvaluator.evaluate(
                l1, l1HasError,
                l2Available ? l2 : null,
                l3,
                sampleOk);
    }

    // ------------------------------------------------------------- assemble

    private Map<String, Object> reportMap(SkillRefs refs,
                                          SkillOverallEvaluator.OverallReport overall,
                                          Map<String, Object> fileCheck,
                                          Map<String, Object> wording,
                                          Map<String, Object> effect,
                                          Instant analyzedAt) {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("overallScore", overall.getOverallScore());
        report.put("conclusion", mapConclusion(overall.getConclusion()));
        report.put("analyzedAt", analyzedAt == null ? Instant.now().toString() : analyzedAt.toString());
        report.put("skillVersion", refs.versionLabel);
        report.put("fileCheck", fileCheck == null ? Collections.emptyMap() : fileCheck);
        report.put("wording", wording == null ? Collections.emptyMap() : wording);
        report.put("effect", effect == null ? Collections.emptyMap() : effect);
        report.put("suggestions", aggregateSuggestions(fileCheck, wording, effect));
        report.put("status", STATUS_OK);
        return report;
    }

    private Map<String, Object> fromStored(Map<String, Object> stored, SkillRefs refs) {
        Map<String, Object> fileCheck = parseSection(stringOf(stored.get("l1_json")));
        Map<String, Object> wording = parseSection(stringOf(stored.get("l2_json")));
        Map<String, Object> effect = parseSection(stringOf(stored.get("l3_json")));
        fileCheck = ensureFileCheckDimensions(
                isUsableSection(fileCheck) ? fileCheck : fileCheckSection(null));
        wording = ensureWordingDimensions(
                isUsableSection(wording) ? wording : wordingSection(null));
        if (!isUsableSection(effect)) {
            effect = effectSection(null, null, ObservationMetrics.unavailable());
        } else {
            ensureEffectShape(effect);
        }
        ensureIssueDeductions(fileCheck);
        ensureIssueDeductions(wording);
        ensureIssueDeductions(effect);

        // 综合分与三块同源重算，避免历史 overall 与展示分不一致
        SkillOverallEvaluator.OverallReport overall = overallFromSections(fileCheck, wording, effect);
        Instant analyzedAt = instantOf(stored.get("created_at"));
        return reportMap(refs, overall, fileCheck, wording, effect, analyzedAt);
    }

    private void persist(SkillRefs refs,
                         Map<String, Object> fileCheck,
                         Map<String, Object> wording,
                         Map<String, Object> effect,
                         SkillOverallEvaluator.OverallReport overall,
                         String chainSource) {
        String methodNote = "w=" + configService.w1() + "/" + configService.w2() + "/" + configService.w3()
                + "; threshold=" + configService.optimizeThreshold() + "/" + configService.watchUpper()
                + "; chainSource=" + chainSource;
        Double overallScore = overall.getOverallScore() == null
                ? null : Double.valueOf(overall.getOverallScore().doubleValue());
        reportRepository.insert(refs.skillId, refs.versionId,
                toJson(fileCheck), toJson(wording), toJson(effect),
                overallScore, mapConclusion(overall.getConclusion()),
                methodNote, configService.ruleVersion());
    }

    // ------------------------------------------------------------- resolve

    private SkillRefs resolve(String slug, String version) {
        if (slug == null || slug.trim().isEmpty()) {
            throw new SkillApiException("slug 不能为空", SkillApiException.CODE_VALIDATION_ERROR);
        }
        String normalizedSlug = slug.trim();
        Map<String, Object> detail = skillRepository.detail(normalizedSlug);
        // detail() 抛 SKILL_NOT_FOUND；此处兜底
        if (detail == null || detail.get("id") == null) {
            throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
        }
        String label = SkillVersions.normalizeVersionLabel(version);
        if (label != null && !SkillVersions.isSemVer(label)) {
            throw new SkillApiException(
                    "version 必须使用包内声明的语义化版本号（如 1.0.0）",
                    SkillApiException.CODE_VERSION_INVALID);
        }
        Long versionId = skillRepository.versionId(normalizedSlug, label == null ? "" : label);
        SkillRefs refs = new SkillRefs();
        refs.slug = normalizedSlug;
        refs.skillId = Long.valueOf(((Number) detail.get("id")).longValue());
        refs.versionId = versionId;
        refs.versionLabel = label != null ? label : reportRepository.versionLabel(versionId);
        if (refs.versionLabel == null) {
            refs.versionLabel = stringOf(detail.get("version_label"));
        }
        return refs;
    }

    private List<SkillFile> loadFiles(Long versionId) {
        List<SkillFile> files = new ArrayList<SkillFile>();
        if (versionId == null) return files;
        try {
            List<Map<String, Object>> rows = reportRepository.skillFileTexts(versionId);
            for (Map<String, Object> row : rows) {
                files.add(new SkillFile(stringOf(row.get("path")), stringOf(row.get("content"))));
            }
        } catch (RuntimeException ex) {
            // 文件不可读 → 空包由静态检查降级
            files.clear();
        }
        return files;
    }

    private String skillContent(List<SkillFile> files) {
        StringBuilder builder = new StringBuilder();
        for (SkillFile file : files) {
            if (file == null || file.getPath() == null) continue;
            String name = file.getPath().toLowerCase(Locale.ROOT);
            boolean isDoc = name.endsWith(".md") || name.endsWith(".markdown")
                    || name.endsWith(".txt") || name.contains("skill.md") || name.endsWith("readme");
            if (!isDoc) continue;
            if (builder.length() > 12000) break;
            builder.append("\n--- ").append(file.getPath()).append(" ---\n")
                    .append(file.getContent() == null ? "" : file.getContent());
        }
        return builder.toString();
    }

    // ------------------------------------------------------------- metrics

    /** 复用 ObservationRepository 行为指标；查询失败降级为「无数据」。 */
    protected ObservationMetrics loadMetrics(SkillRefs refs) {
        try {
            Map<String, Object> quality = observationRepository.skillQualitySummary(
                    refs.slug, refs.versionLabel);
            Map<String, Object> usage = observationRepository.skillTokenSummary(
                    refs.slug, refs.versionLabel);
            int calls = intOf(quality.get("calls"));
            long totalTokens = longOf(usage.get("totalTokens"));
            int requests = intOf(usage.get("requestCount"));
            long denominator = requests > 0 ? requests : calls;
            long avgTokens = denominator <= 0L ? 0L : totalTokens / denominator;
            return new ObservationMetrics(
                    calls,
                    intOf(quality.get("skillTurns")),
                    intOf(quality.get("sessions")),
                    intOf(quality.get("errors")),
                    intOf(quality.get("reloadTurns")),
                    doubleOf(quality.get("errorRate")),
                    doubleOf(quality.get("reloadRate")),
                    doubleOf(quality.get("checklistCoverage")),
                    avgTokens);
        } catch (RuntimeException ex) {
            return ObservationMetrics.unavailable();
        }
    }

    // ------------------------------------------------------------- helpers

    static String mapConclusion(String raw) {
        if (raw == null) return CONCLUSION_NO_DATA;
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (SkillOverallEvaluator.CONCLUSION_NEED_OPTIMIZE.toLowerCase(Locale.ROOT).equals(value)
                || CONCLUSION_NEED_OPTIMIZE.equals(value)) {
            return CONCLUSION_NEED_OPTIMIZE;
        }
        if (SkillOverallEvaluator.CONCLUSION_WATCH.toLowerCase(Locale.ROOT).equals(value)
                || CONCLUSION_WATCH.equals(value)) {
            return CONCLUSION_WATCH;
        }
        if (SkillOverallEvaluator.CONCLUSION_KEEP.toLowerCase(Locale.ROOT).equals(value)
                || CONCLUSION_KEEP.equals(value)) {
            return CONCLUSION_KEEP;
        }
        return CONCLUSION_NO_DATA;
    }

    static String mapCost(String costLevel) {
        if ("high".equals(costLevel)) return "high";
        if ("normal".equals(costLevel)) return "normal";
        return "unknown";
    }

    private static Integer sectionScore(Map<String, Object> section) {
        if (section == null) return null;
        Object score = section.get("score");
        return score instanceof Number
                ? Integer.valueOf(((Number) score).intValue()) : null;
    }

    private static boolean isUsableSection(Map<String, Object> section) {
        return section != null && !section.isEmpty();
    }

    /** 存量 effect 行缺字段时补齐契约键（不覆盖已有值）。 */
    static void ensureEffectShape(Map<String, Object> effect) {
        if (effect == null) return;
        if (!effect.containsKey("score")) effect.put("score", null);
        if (!effect.containsKey("completionRate")) effect.put("completionRate", null);
        if (!effect.containsKey("errorRate")) effect.put("errorRate", null);
        if (!effect.containsKey("cost")) effect.put("cost", "unknown");
        if (!effect.containsKey("commonIssues")) {
            effect.put("commonIssues", new ArrayList<Map<String, Object>>());
        }
        if (!effect.containsKey("problems")) effect.put("problems", new ArrayList<String>());
        if (!effect.containsKey("fixes")) effect.put("fixes", new ArrayList<String>());
        if (!effect.containsKey("suggestions")) {
            effect.put("suggestions", new ArrayList<Map<String, Object>>());
        }
    }

    private static Map<String, Object> issueRow(String severity, String problem, String fix,
                                                String dimension) {
        return issueRow(severity, problem, fix, dimension, null);
    }

    private static Map<String, Object> issueRow(String severity, String problem, String fix,
                                                String dimension, Integer deduction) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("severity", severity);
        row.put("problem", problem == null ? "" : problem);
        row.put("fix", fix == null ? "" : fix);
        if (dimension != null && !dimension.isEmpty()) {
            row.put("dimension", dimension);
        }
        row.put("deduction", Integer.valueOf(problemDeduction(severity, deduction)));
        row.put("score", row.get("deduction"));
        return row;
    }

    /**
     * 问题扣分（从所属维度汇总分中扣除）。
     * 通用档：严重 20 / 一般 10 / 轻微 5；显式 override 上限 30。
     */
    static int problemDeduction(String severity, Integer override) {
        if (override != null && override.intValue() > 0) {
            int v = override.intValue();
            return v > 30 ? 30 : v;
        }
        if ("high".equals(severity)) return 20;
        if ("low".equals(severity)) return 5;
        return 10;
    }

    /** L1：错误 20，警告 8。 */
    static int l1Deduction(String level) {
        return SkillStaticChecker.LEVEL_ERROR.equals(level) ? 20 : 8;
    }

    /** L2：基础档 × 维度权重系数（权重≥18 → 1.2；≥12 → 1.1；其余 1.0），上限 30。 */
    static int l2Deduction(String severity, int dimWeight) {
        double factor = 1.0d;
        if (dimWeight >= 18) factor = 1.2d;
        else if (dimWeight >= 12) factor = 1.1d;
        int base = problemDeduction(severity, null);
        int v = (int) Math.round(base * factor);
        return v > 30 ? 30 : (v < 1 ? 1 : v);
    }

    /** L3：按出现次数定档。 */
    static int l3Deduction(int count, String severity) {
        if (count >= 10) return 20;
        if (count >= 3) return 10;
        return problemDeduction(severity, null);
    }

    /**
     * 维度汇总扣分 = 该维问题扣分合计；summaryScore = 100 − 汇总扣分（下限 0）。
     */
    static void applyDimensionAggregates(List<Map<String, Object>> dims,
                                         List<Map<String, Object>> issueRows) {
        if (dims == null) return;
        Map<String, Integer> deduct = new LinkedHashMap<String, Integer>();
        if (issueRows != null) {
            for (Map<String, Object> row : issueRows) {
                if (row == null) continue;
                String dim = row.get("dimension") == null ? "" : String.valueOf(row.get("dimension"));
                int d = 0;
                if (row.get("deduction") instanceof Number) {
                    d = ((Number) row.get("deduction")).intValue();
                } else if (row.get("score") instanceof Number) {
                    d = ((Number) row.get("score")).intValue();
                }
                Integer prev = deduct.get(dim);
                deduct.put(dim, Integer.valueOf((prev == null ? 0 : prev.intValue()) + d));
            }
        }
        for (Map<String, Object> dim : dims) {
            String name = String.valueOf(dim.get("name"));
            Integer d = deduct.get(name);
            int totalDeduct = d == null ? 0 : d.intValue();
            if (totalDeduct > 100) totalDeduct = 100;
            dim.put("deduction", Integer.valueOf(totalDeduct));
            dim.put("summaryScore", Integer.valueOf(100 - totalDeduct));
        }
    }

    /** 由维度汇总得分加权计算 L1/L3 分数。 */
    static int weightedSectionScore(List<Map<String, Object>> dims) {
        if (dims == null || dims.isEmpty()) return 0;
        double weighted = 0d;
        double weightSum = 0d;
        for (Map<String, Object> dim : dims) {
            int weight = dim.get("weight") instanceof Number
                    ? ((Number) dim.get("weight")).intValue() : 1;
            int summary = dim.get("summaryScore") instanceof Number
                    ? ((Number) dim.get("summaryScore")).intValue() : 100;
            weighted += summary * weight;
            weightSum += weight;
        }
        return weightSum <= 0d ? 0 : (int) Math.round(weighted / weightSum);
    }

    private static List<Map<String, Object>> sortBySeverity(List<Map<String, Object>> rows) {
        List<Map<String, Object>> sorted = new ArrayList<Map<String, Object>>(rows);
        Collections.sort(sorted, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Integer.valueOf(severityRank(String.valueOf(a.get("severity"))))
                        .compareTo(Integer.valueOf(severityRank(String.valueOf(b.get("severity")))));
            }
        });
        return sorted;
    }

    private static int severityRank(String severity) {
        return "high".equals(severity) ? 0 : ("normal".equals(severity) ? 1 : 2);
    }

    private Issue worstIssue(List<Issue> issues, String metricCode) {
        Issue warn = null;
        if (issues == null) return null;
        for (Issue issue : issues) {
            if (issue == null || !metricCode.equals(issue.getMetricCode())) continue;
            if (SkillStaticChecker.LEVEL_ERROR.equals(issue.getLevel())) return issue;
            if (warn == null) warn = issue;
        }
        return warn;
    }

    private static String defaultWordingFix(int index, String label) {
        switch (index) {
            case 0: return "补齐规范 frontmatter：name + description（做什么、何时用）";
            case 1: return "把工作流写成有序、可执行的步骤，标注每步输入输出";
            case 2: return "补上失败分支与恢复策略（若 X 失败则 Y）";
            case 3: return "在高风险操作前加显性确认点";
            case 4: return "用具体参数与示例替换软化措辞（建议/酌情/视情况）";
            case 5: return "核对 references/scripts/assets 引用路径真实可达";
            case 6: return "理顺章节结构，删除冗余并补齐遗漏";
            case 7: return "补一条「不要做什么」反例 / 禁止清单";
            default: return "按「" + label + "」评审意见改写";
        }
    }

    private int indexOfDim(List<SkillQualityScorer.Dimension> source,
                           SkillQualityScorer.Dimension target) {
        if (source == null || target == null) return -1;
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) == target) return i;
            SkillQualityScorer.Dimension dim = source.get(i);
            if (dim != null && target.getName() != null
                    && target.getName().equals(dim.getName())) {
                return i;
            }
        }
        return -1;
    }

    private List<Map<String, Object>> aggregateSuggestions(Map<String, Object> fileCheck,
                                                           Map<String, Object> wording,
                                                           Map<String, Object> effect) {
        List<Map<String, Object>> all = new ArrayList<Map<String, Object>>();
        all.addAll(asSuggestionList(fileCheck == null ? null : fileCheck.get("suggestions")));
        all.addAll(asSuggestionList(wording == null ? null : wording.get("suggestions")));
        all.addAll(asSuggestionList(effect == null ? null : effect.get("suggestions")));
        return all;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asSuggestionList(Object value) {
        if (!(value instanceof List)) return Collections.emptyList();
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> suggestion(String title, String detail,
                                                  String howToFix, String area) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("title", title == null ? "" : title);
        item.put("detail", detail == null ? "" : detail);
        item.put("howToFix", howToFix == null ? "" : howToFix);
        item.put("area", area);
        return item;
    }

    // ------------------------------------------------------------- backfill

    /** 旧库或缺字段时补齐格式检查维度表（维度/权重/说明/得分）。 */
    static Map<String, Object> ensureFileCheckDimensions(Map<String, Object> fileCheck) {
        if (fileCheck == null) fileCheck = new LinkedHashMap<String, Object>();
        Object dimsNode = fileCheck.get("dimensions");
        List<Map<String, Object>> dims = new ArrayList<Map<String, Object>>();
        if (dimsNode instanceof List) {
            for (Object item : (List<?>) dimsNode) {
                if (item instanceof Map) {
                    dims.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
                }
            }
        }
        Map<String, Integer> scoreByName = new LinkedHashMap<String, Integer>();
        Object itemsNode = fileCheck.get("items");
        if (itemsNode instanceof List) {
            for (Object item : (List<?>) itemsNode) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> row = (Map<String, Object>) item;
                String name = row.get("name") == null ? "" : String.valueOf(row.get("name"));
                String status = row.get("status") == null ? "pass" : String.valueOf(row.get("status"));
                int score = "fail".equals(status) ? 20 : ("warn".equals(status) ? 55 : 100);
                scoreByName.put(name, Integer.valueOf(score));
            }
        }
        if (dims.size() < FILE_METRICS.length) {
            dims.clear();
            for (String[] metric : FILE_METRICS) {
                Map<String, Object> dim = new LinkedHashMap<String, Object>();
                dim.put("key", metric[0]);
                dim.put("name", metric[1]);
                dim.put("weight", Integer.valueOf(metric[2]));
                dim.put("description", metric[3]);
                Integer fromItems = scoreByName.get(metric[1]);
                dim.put("score", fromItems == null ? Integer.valueOf(100) : fromItems);
                dims.add(dim);
            }
        } else {
            for (Map<String, Object> dim : dims) {
                if (dim.get("weight") == null || dim.get("description") == null) {
                    String name = String.valueOf(dim.get("name"));
                    for (String[] metric : FILE_METRICS) {
                        if (metric[1].equals(name)) {
                            dim.put("weight", Integer.valueOf(metric[2]));
                            dim.put("description", metric[3]);
                            if (dim.get("key") == null) dim.put("key", metric[0]);
                            break;
                        }
                    }
                }
            }
        }
        fileCheck.put("dimensions", dims);
        return fileCheck;
    }

    /** 旧库或缺字段时补齐内容检查维度表。 */
    static Map<String, Object> ensureWordingDimensions(Map<String, Object> wording) {
        if (wording == null) wording = new LinkedHashMap<String, Object>();
        Object dimsNode = wording.get("dimensions");
        List<Map<String, Object>> dims = new ArrayList<Map<String, Object>>();
        if (dimsNode instanceof List) {
            for (Object item : (List<?>) dimsNode) {
                if (item instanceof Map) {
                    dims.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
                }
            }
        }
        if (dims.size() < WORDING_DIMS.length) {
            List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < WORDING_DIMS.length; i++) {
                Map<String, Object> dim = new LinkedHashMap<String, Object>();
                dim.put("key", "w" + (i + 1));
                dim.put("name", WORDING_DIMS[i][0]);
                dim.put("weight", Integer.valueOf(WORDING_DIMS[i][1]));
                dim.put("description", WORDING_DIMS[i][2]);
                dim.put("score", Integer.valueOf(0));
                for (Map<String, Object> old : dims) {
                    if (WORDING_DIMS[i][0].equals(String.valueOf(old.get("name")))) {
                        if (old.get("score") != null) dim.put("score", old.get("score"));
                        if (old.get("gapNote") != null) dim.put("gapNote", old.get("gapNote"));
                        break;
                    }
                }
                merged.add(dim);
            }
            dims = merged;
        } else {
            for (int i = 0; i < dims.size() && i < WORDING_DIMS.length; i++) {
                Map<String, Object> dim = dims.get(i);
                if (dim.get("weight") == null) {
                    dim.put("weight", Integer.valueOf(WORDING_DIMS[i][1]));
                }
                if (dim.get("description") == null) {
                    dim.put("description", WORDING_DIMS[i][2]);
                }
                if (dim.get("key") == null) {
                    dim.put("key", "w" + (i + 1));
                }
            }
        }
        wording.put("dimensions", dims);
        return wording;
    }

    /** 读库时回填问题扣分，并重算维度汇总扣分/汇总得分。 */
    @SuppressWarnings("unchecked")
    static void ensureIssueDeductions(Map<String, Object> section) {
        if (section == null) return;
        Object rowsNode = section.get("issueRows");
        if (!(rowsNode instanceof List)) return;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Object item : (List<?>) rowsNode) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> row = new LinkedHashMap<String, Object>((Map<String, Object>) item);
            if (!(row.get("deduction") instanceof Number)) {
                int d;
                if (row.get("score") instanceof Number) {
                    d = ((Number) row.get("score")).intValue();
                } else {
                    d = problemDeduction(String.valueOf(row.get("severity")), null);
                }
                row.put("deduction", Integer.valueOf(d));
                row.put("score", Integer.valueOf(d));
            }
            rows.add(row);
        }
        section.put("issueRows", rows);
        Object dimsNode = section.get("dimensions");
        if (dimsNode instanceof List) {
            List<Map<String, Object>> dims = (List<Map<String, Object>>) dimsNode;
            applyDimensionAggregates(dims, rows);
            // 写法段在 LLM 不可用时保持 0 分（unavailable），不被空维度汇总冲成 100
            if (!dims.isEmpty() && !Boolean.TRUE.equals(section.get("unavailable"))) {
                section.put("score", Integer.valueOf(weightedSectionScore(dims)));
            }
        }
    }

    // ------------------------------------------------------------- io utils

    private String toJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value == null ? Collections.emptyMap() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSection(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return jsonMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static double doubleOf(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0d;
    }

    private static long longOf(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static String isoOf(Object value) {
        Instant instant = instantOf(value);
        return instant == null ? null : instant.toString();
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Timestamp) return ((Timestamp) value).toInstant();
        if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
        if (value instanceof String) {
            try {
                return Instant.parse((String) value);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /** Resolved skill + version identity for one report scope. */
    static class SkillRefs {
        String slug;
        Long skillId;
        Long versionId;
        String versionLabel;
    }
}
