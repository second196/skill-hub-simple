package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.observation.ObservationRepository;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Checks how a skill was actually executed, using only rules and SQL.
 *
 * <p>Cost model: this class never calls a model. It narrows thousands of turns down to a
 * short list of turns worth a human look, plus a completeness score against the skill's
 * own expected steps. Everything it produces is explainable: each finding carries the rule
 * that fired and the exact step it came from.
 *
 * <p>Scale: rules run over the most recent turns for one skill, not over all history, and
 * results are cached, so the work does not grow with the number of reporting clients.
 */
@Service
public class ObservationAnalyzer {

    /** Composite parents group sub-skills; they are containers, not competing skills. */
    private static final Set<String> COMPOSITE_PARENTS = new HashSet<String>(
            Arrays.asList("using-product-development", "superpowers"));

    private static final String CONTRACT_SYSTEM_PROMPT =
            "你是 skill 契约提取器。请把 SKILL.md 和引用资源整理成预期执行清单，"
                    + "只输出 JSON，字段用 camelCase：{\"steps\":[{\"id\":\"s1\",\"title\":\"\","
                    + "\"description\":\"\",\"keywords\":[],\"expectedTools\":[],\"expectedArtifacts\":[],"
                    + "\"optional\":false,\"parallelGroup\":null}],\"triggers\":[],\"forbidden\":[],\"entryCommands\":[]}。"
                    + "没有可执行步骤时输出 {\"steps\":[]}。";

    private static final String VERDICT_SYSTEM_PROMPT =
            "你是 skill 执行质量判定器。给定契约摘要和回合动作摘要，只输出 JSON："
                    + "{\"verdict\":\"complete|partial|failed|conflicted\",\"missedSteps\":[],"
                    + "\"extraneousActions\":[],\"conflictWith\":[],\"evidence\":[],\"confidence\":0.0}。";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SkillContractRepository contracts;
    private final SkillAnalysisRepository analyses;
    private final AnalysisModelClient modelClient;
    private final int maxTurns;
    private final int maxSessionsForPaths;
    private final double coverageWarnThreshold;
    private final int conflictMinTurns;
    private final int maxLlmSamples;
    private final double llmCoverageThreshold;
    private final double p95Quantile;
    private final Map<String, List<Float>> embeddingCache = new ConcurrentHashMap<String, List<Float>>();

    public ObservationAnalyzer(JdbcTemplate jdbc, ObjectMapper mapper,
                               SkillContractRepository contracts, SkillAnalysisRepository analyses,
                               AnalysisModelClient modelClient,
                               @Value("${skillhub.analysis.max-turns:200}") int maxTurns,
                               @Value("${skillhub.analysis.max-sessions-for-paths:100}") int maxSessionsForPaths,
                               @Value("${skillhub.analysis.coverage-warn-threshold:0.8}") double coverageWarnThreshold,
                               @Value("${skillhub.analysis.conflict-min-turns:2}") int conflictMinTurns,
                               @Value("${skillhub.analysis.llm.sample-limit:20}") int maxLlmSamples,
                               @Value("${skillhub.analysis.llm.coverage-threshold:0.6}") double llmCoverageThreshold,
                               @Value("${skillhub.analysis.p95-quantile:0.95}") double p95Quantile) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.contracts = contracts;
        this.analyses = analyses;
        this.modelClient = modelClient;
        this.maxTurns = maxTurns;
        this.maxSessionsForPaths = maxSessionsForPaths;
        this.coverageWarnThreshold = coverageWarnThreshold;
        this.conflictMinTurns = conflictMinTurns;
        this.maxLlmSamples = maxLlmSamples;
        this.llmCoverageThreshold = llmCoverageThreshold;
        this.p95Quantile = p95Quantile;
    }

    // ------------------------------------------------------------- contract

    /**
     * Parse and cache the expected-step contract for a skill version.
     *
     * <p>A published version is immutable, so an existing contract is always reused and
     * only missing ones (or an explicit refresh) trigger a re-parse.
     *
     * @param force re-parse even when a stored contract exists
     * @return the stored contract view
     */
    public Map<String, Object> ensureContract(String slug, String versionLabel, boolean force) {
        String version = resolveVersionLabel(slug, versionLabel);
        if (version == null) return null;
        if (!force && contracts.exists(slug, version)) {
            return contracts.findContract(slug, version);
        }
        List<SkillContractExtractor.Source> files = contracts.filesForVersion(slug, version);
        if (files.isEmpty()) return null;

        SkillContractExtractor extractor = new SkillContractExtractor();
        SkillContractExtractor.Result result = extractor.extract(files);
        if (modelClient.isEnabled() && !"rule".equals(result.getParseMethod())) {
            extractContractWithModel(slug, files, result);
        }
        contracts.upsert(slug, version, result);
        return contracts.findContract(slug, version);
    }

    /** Optional fallback when the rule parser cannot produce a checklist. */
    private void extractContractWithModel(String slug, List<SkillContractExtractor.Source> files,
                                          SkillContractExtractor.Result result) {
        try {
            StringBuilder input = new StringBuilder();
            for (SkillContractExtractor.Source file : files) {
                if (input.length() > 16000) break;
                input.append("\n--- ").append(file.getPath()).append(" ---\n")
                        .append(cap(file.getContent(), 6000));
            }
            String json = modelClient.chat(slug, CONTRACT_SYSTEM_PROMPT, input.toString()).orElse(null);
            if (json == null) json = modelClient.chat(slug, CONTRACT_SYSTEM_PROMPT, input.toString()).orElse(null);
            if (json == null) return;
            Map<String, Object> root = mapper.readValue(extractJsonObject(json), Map.class);
            SkillContract contract = contractFromJson(root);
            if (contract == null || contract.getSteps().isEmpty()) return;
            result.setContract(contract);
            result.setParseMethod("llm");
            result.getMeta().put("confidence", Double.valueOf(0.7d));
            result.getMeta().put("model", "configured");
            result.getMeta().remove("error");
        } catch (RuntimeException ex) {
            result.getMeta().put("llm_error", ex.getMessage());
        } catch (Exception ex) {
            result.getMeta().put("llm_error", ex.getMessage());
        }
    }

    /** Blank and "all" resolve to the newest published version. */
    public String resolveVersionLabel(String slug, String versionLabel) {
        if (versionLabel != null && !versionLabel.trim().isEmpty() && !"all".equalsIgnoreCase(versionLabel.trim())) {
            return com.km.skillhub.skill.SkillVersions.normalizeVersionLabel(versionLabel);
        }
        return contracts.latestVersionLabel(slug);
    }

    // -------------------------------------------------------------- analyze

    /**
     * Full analysis for one (slug, version): problems, completeness, usage patterns.
     * Results are persisted so repeat visits are cheap.
     */
    public Map<String, Object> analyze(String slug, String versionLabel, boolean refresh) {
        String version = resolveVersionLabel(slug, versionLabel);
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("slug", slug);
        report.put("versionLabel", version == null ? "" : version);
        if (version == null) {
            report.put("degraded", Boolean.TRUE);
            report.put("message", "该技能在平台上还没有已发布的版本，暂时无法分析");
            return report;
        }

        Map<String, Object> contract = ensureContract(slug, version, refresh);
        List<SkillContract.Step> steps = contractSteps(contract);
        boolean hasContract = contract != null && !steps.isEmpty();

        report.put("contract", contractView(contract, steps));

        List<TurnData> turns = loadTurns(slug, version);
        List<Map<String, Object>> problems = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> coverageRows = new ArrayList<Map<String, Object>>();

        long tokenP95 = percentile(collectTurnTokens(turns, slug), p95Quantile);
        long stepP95 = percentile(collectTurnStepCounts(turns), p95Quantile);

        Map<String, Integer> missedCount = new LinkedHashMap<String, Integer>();
        int deviationTotal = 0;
        int coveredTurns = 0;
        double coverageSum = 0d;
        List<Map<String, Object>> llmVerdicts = new ArrayList<Map<String, Object>>();
        List<LlmCandidate> llmCandidates = new ArrayList<LlmCandidate>();
        int llmBudget = modelClient.isEnabled()
                ? Math.max(0, maxLlmSamples - analyses.countRecent(slug, version, SkillAnalysisRepository.TYPE_VERDICT, 7))
                : 0;

        for (TurnData turn : turns) {
            List<StepRow> skillSteps = skillStepsOf(turn, slug);
            if (skillSteps.isEmpty()) continue;

            // R1 — the skill call itself failed.
            for (StepRow step : skillSteps) {
                if (isErrorStep(step)) {
                    problems.add(problem("R1", "载入报错", "P0", turn,
                            "载入技能时报错，后面的步骤没能继续"));
                    break;
                }
            }

            // R2 — loaded the skill, then did nothing with it.
            int firstSeq = skillSteps.get(0).seq;
            boolean hasFollowUp = false;
            for (StepRow step : turn.steps) {
                if (step.seq > firstSeq && ("tool".equals(step.type) || "document".equals(step.type))) {
                    hasFollowUp = true;
                    break;
                }
            }
            if (!hasFollowUp) {
                problems.add(problem("R2", "载入后没有动作", "P1", turn,
                        "读入了技能但没有执行任何实际操作"));
            }

            // R3 — same skill loaded twice in one turn.
            if (skillSteps.size() >= 2) {
                problems.add(problem("R3", "重复载入", "P2", turn,
                        "同一轮对话里重复载入了该技能 " + skillSteps.size() + " 次"));
            }

            // R4 — several different skills used in one turn.
            Set<String> distinct = conflictKeys(turn);
            if (distinct.size() >= 2) {
                List<String> names = new ArrayList<String>(distinct);
                problems.add(problem("R4", "多个技能混用", "P1", turn,
                        "同一轮对话里同时用到了 " + names.size() + " 个技能：" + join(names, "、")));
            }

            // R5 — this turn is far longer than usual.
            if (stepP95 > 0 && turn.steps.size() > stepP95) {
                problems.add(problem("R5", "单轮步骤过多", "P2", turn,
                        "这一轮的操作步骤是 " + turn.steps.size() + " 步，明显多于常见水平（" + stepP95 + " 步）"));
            } else if (tokenP95 > 0) {
                long tokens = turnTokens(turn, slug);
                if (tokens > tokenP95) {
                    problems.add(problem("R5", "单轮消耗偏高", "P2", turn,
                            "这一轮消耗 " + tokens + " tokens，高于常见水平（" + tokenP95 + "）"));
                }
            }

            if (hasContract) {
                Map<String, Object> row = coverageOf(turn, slug, steps, missedCount);
                row.put("turnId", Long.valueOf(turn.turnId));
                row.put("turnIndex", Integer.valueOf(turn.turnIndex));
                row.put("clientId", turn.clientId);
                coverageRows.add(row);
                coveredTurns += 1;
                coverageSum += doubleOf(row.get("checklistCoverage"), 0d);
                deviationTotal += intOf(row.get("deviationCount"));
                if (Boolean.TRUE.equals(row.get("orderViolation"))) {
                    problems.add(problem("R6", "步骤顺序不一致", "P1", turn,
                            "实际执行顺序和技能里写的顺序不一样"));
                }
                @SuppressWarnings("unchecked")
                List<String> missing = (List<String>) row.get("missedTitles");
                if (!missing.isEmpty()) {
                    problems.add(problem("R7", "步骤缺失", "P1", turn,
                            "没有执行技能要求的步骤：" + join(missing, "、")));
                }
                if (intOf(row.get("deviationCount")) > 0) {
                    problems.add(problem("R8", "出现了额外操作", "P2", turn,
                            "做了 " + row.get("deviationCount") + " 个技能里没有提到的操作"));
                }
            }

            if (llmBudget > 0 && shouldReview(turn, problems, coverageRows)) {
                llmCandidates.add(new LlmCandidate(turn, severityOf(turn, problems, coverageRows),
                        failurePatternOf(turn, problems, coverageRows)));
            }
        }

        for (LlmCandidate candidate : selectLlmSamples(llmCandidates, llmBudget)) {
            Map<String, Object> verdict = llmVerdictOf(slug, version, candidate.turn, steps);
            if (verdict != null) llmVerdicts.add(verdict);
        }

        // R9 — the user is running an older version than the platform's newest.
        String latest = contracts.latestVersionLabel(slug);
        if (latest != null && !latest.equalsIgnoreCase(version)) {
            Map<String, Object> item = problem("R9", "使用的版本偏旧", "P3", null,
                    "本次分析基于 " + version + "，平台最新版本是 " + latest + "；旧版本的问题可能已经修好");
            problems.add(item);
        }

        double checklistCoverage = coveredTurns == 0 ? -1d : round2(coverageSum / coveredTurns);
        report.put("summary", buildSummary(turns, problems, checklistCoverage, coveredTurns, deviationTotal));
        report.put("problems", problems);
        report.put("coverage", buildCoverage(checklistCoverage, steps, missedCount, coverageRows));
        report.put("conflicts", conflictsFor(slug));
        report.put("paths", pathsFor(slug));
        report.put("llmVerdicts", llmVerdicts);
        report.put("suggestions", buildSuggestions(problems, checklistCoverage, steps, missedCount));

        persist(slug, version, problems, coverageRows, checklistCoverage);
        persistAggregates(slug, version);
        return report;
    }

    // --------------------------------------------------------------- rules

    private Map<String, Object> problem(String ruleId, String title, String severity, TurnData turn, String detail) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("ruleId", ruleId);
        item.put("title", title);
        item.put("severity", severity);
        item.put("detail", detail);
        if (turn != null) {
            item.put("turnId", Long.valueOf(turn.turnId));
            item.put("turnIndex", Integer.valueOf(turn.turnIndex));
            item.put("sessionId", Long.valueOf(turn.sessionId));
            item.put("clientId", turn.clientId);
            item.put("userText", cap(turn.userText, 200));
        }
        return item;
    }

    private Map<String, Object> buildSummary(List<TurnData> turns, List<Map<String, Object>> problems,
                                             double checklistCoverage, int coveredTurns, int deviationTotal) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        int problemTurns = 0;
        Set<Long> seen = new HashSet<Long>();
        for (Map<String, Object> problem : problems) {
            Object turnId = problem.get("turnId");
            if (turnId instanceof Number && seen.add(((Number) turnId).longValue())) problemTurns += 1;
        }
        summary.put("turnsAnalyzed", Integer.valueOf(turns.size()));
        summary.put("problemTurnCount", Integer.valueOf(problemTurns));
        summary.put("problemCount", Integer.valueOf(problems.size()));
        summary.put("coverageTurns", Integer.valueOf(coveredTurns));
        summary.put("deviationCount", Integer.valueOf(deviationTotal));
        summary.put("checklistCoverage", Double.valueOf(checklistCoverage));
        summary.put("coverageLabel", coverageLabel(checklistCoverage));
        return summary;
    }

    private Map<String, Object> buildCoverage(double checklistCoverage, List<SkillContract.Step> steps,
                                              Map<String, Integer> missedCount, List<Map<String, Object>> rows) {
        Map<String, Object> coverage = new LinkedHashMap<String, Object>();
        coverage.put("checklistCoverage", Double.valueOf(checklistCoverage));
        coverage.put("label", coverageLabel(checklistCoverage));
        coverage.put("hasContract", Boolean.valueOf(!steps.isEmpty()));
        coverage.put("requiredStepCount", Integer.valueOf(countRequired(steps)));

        List<Map<String, Object>> ranked = new ArrayList<Map<String, Object>>();
        for (SkillContract.Step step : steps) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", step.getId());
            item.put("title", step.getTitle());
            item.put("optional", Boolean.valueOf(step.isOptional()));
            item.put("missCount", missedCount.containsKey(step.getId())
                    ? missedCount.get(step.getId()) : Integer.valueOf(0));
            ranked.add(item);
        }
        Collections.sort(ranked, new java.util.Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return intOf(b.get("missCount")) - intOf(a.get("missCount"));
            }
        });
        coverage.put("steps", ranked);

        List<Map<String, Object>> missedTop = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : ranked) {
            if (intOf(item.get("missCount")) > 0 && missedTop.size() < 8) missedTop.add(item);
        }
        coverage.put("mostMissed", missedTop);
        coverage.put("turns", rows.size() > 40 ? rows.subList(0, 40) : rows);
        return coverage;
    }

    /** Match one turn's real actions against the skill's expected steps. */
    private Map<String, Object> coverageOf(TurnData turn, String slug, List<SkillContract.Step> steps,
                                           Map<String, Integer> missedCount) {
        List<StepRow> skillSteps = skillStepsOf(turn, slug);
        int firstSeq = skillSteps.isEmpty() ? 0 : skillSteps.get(0).seq;
        List<StepRow> actions = new ArrayList<StepRow>();
        for (StepRow step : turn.steps) {
            if (step.seq > firstSeq && ("tool".equals(step.type) || "document".equals(step.type))) {
                actions.add(step);
            }
        }

        Map<String, Integer> matchedOrder = new LinkedHashMap<String, Integer>();
        Set<Integer> usedActions = new HashSet<Integer>();
        int matchedRequired = 0;
        int required = 0;
        List<String> missedTitles = new ArrayList<String>();
        for (SkillContract.Step step : steps) {
            if (step.isOptional()) continue;
            required += 1;
            int hit = -1;
            for (int i = 0; i < actions.size(); i++) {
                if (usedActions.contains(Integer.valueOf(i))) continue;
                if (matches(step, actions.get(i))) {
                    hit = i;
                    break;
                }
            }
            if (hit >= 0) {
                usedActions.add(Integer.valueOf(hit));
                matchedRequired += 1;
                matchedOrder.put(step.getId(), Integer.valueOf(hit));
            } else {
                missedTitles.add(cap(step.getTitle(), 40));
                Integer current = missedCount.get(step.getId());
                missedCount.put(step.getId(), Integer.valueOf(current == null ? 1 : current.intValue() + 1));
            }
        }

        int deviations = 0;
        for (int i = 0; i < actions.size(); i++) {
            if (!usedActions.contains(Integer.valueOf(i))) deviations += 1;
        }

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("checklistCoverage", required == 0 ? -1d : round2((double) matchedRequired / required));
        row.put("matchedCount", Integer.valueOf(matchedRequired));
        row.put("requiredCount", Integer.valueOf(required));
        row.put("missedTitles", missedTitles);
        row.put("deviationCount", Integer.valueOf(deviations));
        row.put("orderViolation", Boolean.valueOf(isOrderViolation(steps, matchedOrder)));
        return row;
    }

    private boolean matches(SkillContract.Step step, StepRow action) {
        String text = actionText(action);
        if ("document".equals(action.type)) {
            String path = stringOf(action.payload.get("path"), "");
            for (String artifact : nullSafe(step.getExpectedArtifacts())) {
                if (matchesArtifact(artifact, path) || matchesArtifact(artifact, text)) return true;
            }
        } else {
            String tool = stringOf(action.payload.get("name"), "");
            for (String expected : nullSafe(step.getExpectedTools())) {
                if (expected.equalsIgnoreCase(tool)) return true;
            }
        }
        for (String keyword : nullSafe(step.getKeywords())) {
            if (keyword.length() >= 3 && text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private boolean matchesArtifact(String pattern, String text) {
        if (pattern == null || pattern.isEmpty() || text == null || text.isEmpty()) return false;
        try {
            if (Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE).matcher(text).find()) return true;
        } catch (RuntimeException ignored) {
            // fall through to plain contains
        }
        return text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    /** Ordered steps must appear in order unless they share a parallel group. */
    private boolean isOrderViolation(List<SkillContract.Step> steps, Map<String, Integer> matchedOrder) {
        List<String> ordered = new ArrayList<String>();
        for (SkillContract.Step step : steps) {
            if (step.isOptional() || step.getParallelGroup() != null) continue;
            if (matchedOrder.containsKey(step.getId())) ordered.add(step.getId());
        }
        int last = -1;
        for (String id : ordered) {
            int current = matchedOrder.get(id).intValue();
            if (current < last) return true;
            last = current;
        }
        return false;
    }

    // ------------------------------------------------------------------ LLM (optional)

    /** Only flagged turns are worth a model call: P0/P1 findings or low coverage. */
    private boolean shouldReview(TurnData turn, List<Map<String, Object>> problems,
                                  List<Map<String, Object>> coverageRows) {
        String severity = severityOf(turn, problems, coverageRows);
        return "P0".equals(severity) || "P1".equals(severity);
    }

    /** Highest severity attached to this turn, or null when nothing was flagged. */
    private String severityOf(TurnData turn, List<Map<String, Object>> problems,
                              List<Map<String, Object>> coverageRows) {
        String best = null;
        for (Map<String, Object> problem : problems) {
            if (longOf(problem.get("turnId")) != turn.turnId) continue;
            String severity = stringOf(problem.get("severity"), "");
            if (best == null || severityRank(severity) < severityRank(best)) best = severity;
        }
        for (int i = coverageRows.size() - 1; i >= 0; i--) {
            Map<String, Object> row = coverageRows.get(i);
            if (longOf(row.get("turnId")) != turn.turnId) continue;
            double coverage = doubleOf(row.get("checklistCoverage"), -1d);
            if (coverage >= 0 && coverage < llmCoverageThreshold) return "P1";
            break;
        }
        return best;
    }

    private static int severityRank(String severity) {
        if ("P0".equals(severity)) return 0;
        if ("P1".equals(severity)) return 1;
        if ("P2".equals(severity)) return 2;
        return 3;
    }

    /** Failed-mode key: same rule + same missing steps counts as one pattern. */
    private String failurePatternOf(TurnData turn, List<Map<String, Object>> problems,
                                    List<Map<String, Object>> coverageRows) {
        StringBuilder pattern = new StringBuilder();
        for (Map<String, Object> problem : problems) {
            if (longOf(problem.get("turnId")) != turn.turnId) continue;
            pattern.append(stringOf(problem.get("ruleId"), "")).append('|');
        }
        for (Map<String, Object> row : coverageRows) {
            if (longOf(row.get("turnId")) != turn.turnId) continue;
            @SuppressWarnings("unchecked")
            List<String> missing = (List<String>) row.get("missedTitles");
            if (missing != null) pattern.append(String.join(",", missing));
            break;
        }
        return pattern.toString();
    }

    /** Dedupe by failure mode, then keep one representative per client, then truncate. */
    private List<LlmCandidate> selectLlmSamples(List<LlmCandidate> candidates, int limit) {
        // One representative per (pattern, client). A pattern that only happens on one
        // client is kept; identical failures across clients collapse to one entry first.
        Map<String, LlmCandidate> byPatternClient = new LinkedHashMap<String, LlmCandidate>();
        for (LlmCandidate candidate : candidates) {
            String key = candidate.pattern + "|" + clientOf(candidate);
            LlmCandidate existing = byPatternClient.get(key);
            if (existing == null || severityRank(candidate.severity) < severityRank(existing.severity)) {
                byPatternClient.put(key, candidate);
            }
        }

        // First pass: one representative per failure pattern, whatever the client.
        Map<String, LlmCandidate> byPattern = new LinkedHashMap<String, LlmCandidate>();
        for (LlmCandidate candidate : byPatternClient.values()) {
            LlmCandidate existing = byPattern.get(candidate.pattern);
            if (existing == null || severityRank(candidate.severity) < severityRank(existing.severity)) {
                byPattern.put(candidate.pattern, candidate);
            }
        }
        List<LlmCandidate> selected = new ArrayList<LlmCandidate>(byPattern.values());
        Set<String> selectedKeys = new HashSet<String>();
        for (LlmCandidate candidate : selected) selectedKeys.add(candidate.pattern + "|" + clientOf(candidate));

        // Second pass: if budget remains, add another client's representative so an
        // environment-specific failure is not hidden by a single-client first pick.
        List<LlmCandidate> extra = new ArrayList<LlmCandidate>();
        for (LlmCandidate candidate : byPatternClient.values()) {
            if (selectedKeys.contains(candidate.pattern + "|" + clientOf(candidate))) continue;
            extra.add(candidate);
        }
        Collections.sort(extra, new java.util.Comparator<LlmCandidate>() {
            public int compare(LlmCandidate a, LlmCandidate b) {
                int severity = severityRank(a.severity) - severityRank(b.severity);
                if (severity != 0) return severity;
                return a.turn.clientId.compareTo(b.turn.clientId);
            }
        });
        for (LlmCandidate candidate : extra) {
            if (selected.size() >= limit) break;
            selectedKeys.add(candidate.pattern + "|" + clientOf(candidate));
            selected.add(candidate);
        }

        Collections.sort(selected, new java.util.Comparator<LlmCandidate>() {
                public int compare(LlmCandidate a, LlmCandidate b) {
                    return severityRank(a.severity) - severityRank(b.severity);
                }
            });
        if (selected.size() > limit) selected = new ArrayList<LlmCandidate>(selected.subList(0, limit));
        return selected;
    }

    private static String clientOf(LlmCandidate candidate) {
        return candidate.turn.clientId == null || candidate.turn.clientId.isEmpty()
                ? "unknown" : candidate.turn.clientId;
    }

    /** Compressed turn + contract -> strict JSON verdict; cached by turnId. */
    private Map<String, Object> llmVerdictOf(String slug, String version, TurnData turn, List<SkillContract.Step> steps) {
        Map<String, Object> cached = analyses.findOne(slug, version,
                SkillAnalysisRepository.TYPE_VERDICT, String.valueOf(turn.turnId));
        if (cached != null) return cached;
        try {
            StringBuilder input = new StringBuilder();
            input.append("契约：\n");
            for (SkillContract.Step step : steps) {
                input.append(step.getId()).append(' ').append(step.getTitle()).append(' ')
                        .append(step.getKeywords()).append('\n');
            }
            input.append("\n观测动作序列：\n");
            for (StepRow step : turn.steps) {
                if (!"tool".equals(step.type) && !"document".equals(step.type) && !"skill".equals(step.type)) continue;
                input.append("seq=").append(step.seq).append(" type=").append(step.type)
                        .append(" name=").append(stringOf(step.payload.get("name"), stringOf(step.payload.get("path"), "")))
                        .append(" args=").append(cap(String.valueOf(step.payload.get("args")), 200))
                        .append(" result=").append(cap(stringOf(step.payload.get("result"), ""), 200))
                        .append(" outcome=").append(stringOf(step.payload.get("outcome"), ""))
                        .append('\n');
            }
            String json = modelClient.chat(slug, VERDICT_SYSTEM_PROMPT,
                    AnalysisModelClient.redact(input.toString())).orElse(null);
            if (json == null) return null;
            Map<String, Object> verdict = mapper.readValue(extractJsonObject(json), Map.class);
            verdict.put("turnId", Long.valueOf(turn.turnId));
            analyses.upsert(slug, version, SkillAnalysisRepository.TYPE_VERDICT,
                    String.valueOf(turn.turnId), verdict, "ok");
            return verdict;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Model output may wrap JSON in prose or code fences; pull out the object. */
    private static String extractJsonObject(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return "{}";
        return text.substring(start, end + 1);
    }

    @SuppressWarnings("unchecked")
    private SkillContract contractFromJson(Map<String, Object> root) {
        if (root == null) return null;
        SkillContract contract = new SkillContract();
        Object rawSteps = root.get("steps") == null ? root.get("Steps") : root.get("steps");
        if (!(rawSteps instanceof List)) return contract;
        int index = 0;
        for (Object item : (List<Object>) rawSteps) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> map = (Map<String, Object>) item;
            index += 1;
            SkillContract.Step step = new SkillContract.Step();
            step.setId(stringOf(map.get("id"), "s" + index));
            step.setTitle(stringOf(map.get("title"), ""));
            step.setDescription(stringOf(map.get("description"), ""));
            step.setKeywords(stringList(firstOf(map, "keywords", "Keywords")));
            step.setExpectedTools(stringList(firstOf(map, "expectedTools", "expected_tools")));
            step.setExpectedArtifacts(stringList(firstOf(map, "expectedArtifacts", "expected_artifacts")));
            step.setOptional(Boolean.TRUE.equals(firstOf(map, "optional", "Optional")));
            Object group = firstOf(map, "parallelGroup", "parallel_group");
            step.setParallelGroup(group == null ? null : String.valueOf(group));
            if (!step.getTitle().isEmpty()) contract.getSteps().add(step);
        }
        contract.setTriggers(stringList(firstOf(root, "triggers", "Triggers")));
        contract.setForbidden(stringList(firstOf(root, "forbidden", "Forbidden")));
        contract.setEntryCommands(stringList(firstOf(root, "entryCommands", "entry_commands")));
        return contract;
    }

    private static Object firstOf(Map<String, Object> map, String first, String second) {
        return map.get(first) != null ? map.get(first) : map.get(second);
    }

    // ----------------------------------------------------------- conflicts

    /**
     * Skill pairs that show up together, with parent/child pairs folded away so a
     * composite package never looks like two skills fighting each other.
     *
     * @param slug restrict to one skill, or null for every pair
     */
    public List<Map<String, Object>> allConflicts(String slug) {
        // Both sides must satisfy the membership rule, so a co-occurrence is only reported
        // for skills that were genuinely invoked at a published version.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.skill_slug AS slug_a, b.skill_slug AS slug_b, COUNT(DISTINCT a.turn_id) AS turns " +
                        "FROM observation_step a " +
                        "JOIN observation_step b ON b.turn_id=a.turn_id AND b.skill_slug > a.skill_slug " +
                        "WHERE " + ObservationRepository.invokedSkillStep("a", "a.skill_slug") +
                        "  AND " + ObservationRepository.invokedSkillStep("b", "b.skill_slug") +
                        "  AND COALESCE(a.payload->>'rollup','false') <> 'true' " +
                        "  AND COALESCE(b.payload->>'rollup','false') <> 'true' " +
                        "GROUP BY a.skill_slug, b.skill_slug " +
                        "HAVING COUNT(DISTINCT a.turn_id) >= " + conflictMinTurns + " " +
                        "ORDER BY turns DESC LIMIT 200");
        Map<String, Integer> totalBySlug = turnCountsBySlug();
        Map<String, String> skillTextBySlug = skillTextBySlug();
        List<Map<String, Object>> conflicts = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            String a = stringOf(row.get("slug_a"), "");
            String b = stringOf(row.get("slug_b"), "");
            if (a.isEmpty() || b.isEmpty()) continue;
            // Same composite package means parent and child, not a real conflict.
            if (conflictKey(a).equals(conflictKey(b))) continue;
            if (slug != null && !a.equals(slug) && !b.equals(slug)) continue;
            String other = slug != null && b.equals(slug) ? a : b;
            int together = intOf(row.get("turns"));
            int ownTurns = totalBySlug.containsKey(other) ? totalBySlug.get(other).intValue() : 0;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            if (slug != null) item.put("with", other);
            item.put("a", a);
            item.put("b", b);
            item.put("cooccur", Integer.valueOf(together));
            item.put("otherTurns", Integer.valueOf(ownTurns));
            item.put("share", ownTurns == 0 ? 0d : round2((double) together / ownTurns));
            double lexical = lexicalSimilarityOf(a, b, skillTextBySlug);
            double similarity = similarityOf(a, b, skillTextBySlug);
            item.put("similarity", Double.valueOf(round2(similarity)));
            item.put("jaccard", Double.valueOf(round2(lexical)));
            item.put("suggestion", "同一轮对话里 " + a + " 和 " + b + " 一起出现了 " + together
                    + " 次，相似度约 " + Math.round(similarity * 100) + "%。如果两者职责重叠，建议合并或改写触发条件；如果只是正常配合，可以忽略。");
            conflicts.add(item);
        }
        return conflicts;
    }

    public List<Map<String, Object>> conflictsFor(String slug) {
        return allConflicts(slug);
    }

    private Map<String, Integer> turnCountsBySlug() {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT st.skill_slug, COUNT(DISTINCT st.turn_id) AS turns FROM observation_step st " +
                        "WHERE " + ObservationRepository.invokedSkillStep("st", "st.skill_slug") +
                        "  AND COALESCE(st.payload->>'rollup','false') <> 'true' " +
                        "GROUP BY st.skill_slug");
        for (Map<String, Object> row : rows) {
            counts.put(stringOf(row.get("skill_slug"), ""), Integer.valueOf(intOf(row.get("turns"))));
        }
        return counts;
    }

    /** Lexical stand-in for embedding similarity (E2 fallback, 0 token). */
    private Map<String, String> skillTextBySlug() {
        Map<String, String> text = new HashMap<String, String>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT slug, COALESCE(name,'') AS name, COALESCE(description,'') AS description FROM skill");
        for (Map<String, Object> row : rows) {
            String slug = stringOf(row.get("slug"), "");
            if (slug.isEmpty()) continue;
            text.put(slug, stringOf(row.get("name"), "") + "\n" + stringOf(row.get("description"), ""));
        }
        return text;
    }

    private double similarityOf(String a, String b, Map<String, String> texts) {
        Double semantic = semanticSimilarity(a, b, texts);
        if (semantic != null) return semantic.doubleValue();
        return lexicalSimilarityOf(a, b, texts);
    }

    private double lexicalSimilarityOf(String a, String b, Map<String, String> texts) {
        Set<String> tokensA = similarityTokens(texts.get(a));
        Set<String> tokensB = similarityTokens(texts.get(b));
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0d;
        Set<String> union = new HashSet<String>(tokensA);
        union.addAll(tokensB);
        int shared = 0;
        for (String token : tokensA) {
            if (tokensB.contains(token)) shared += 1;
        }
        return (double) shared / union.size();
    }

    private Double semanticSimilarity(String a, String b, Map<String, String> texts) {
        if (!modelClient.isEnabled()) return null;
        List<Float> left = embeddingOf(a, texts);
        List<Float> right = embeddingOf(b, texts);
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) return null;
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.size(); i++) {
            double x = left.get(i).doubleValue();
            double y = right.get(i).doubleValue();
            dot += x * y;
            leftNorm += x * x;
            rightNorm += y * y;
        }
        if (leftNorm <= 0d || rightNorm <= 0d) return null;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private List<Float> embeddingOf(String slug, Map<String, String> texts) {
        List<Float> cached = embeddingCache.get(slug);
        if (cached != null) return cached;
        String text = texts.get(slug);
        if (text == null || text.trim().isEmpty()) return null;
        Optional<List<Float>> embedded = modelClient.embed(slug, AnalysisModelClient.redact(text));
        if (!embedded.isPresent()) return null;
        embeddingCache.put(slug, embedded.get());
        return embedded.get();
    }

    private Set<String> similarityTokens(String text) {
        Set<String> tokens = new HashSet<String>();
        if (text == null) return tokens;
        for (String piece : text.toLowerCase(Locale.ROOT).split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (piece.length() >= 2) tokens.add(piece);
        }
        return tokens;
    }

    // --------------------------------------------------------------- paths

    /**
     * Compare sessions that used this skill by the skill sequence they followed, so a
     * shorter/cheaper way of getting the same job done becomes visible.
     */
    public List<Map<String, Object>> pathsFor(String slug) {
        // Same membership rule as the overview and the session chain: a step whose observed
        // version matches a published one. Without it, excluded steps would still show up as
        // a "route" on a skill whose usage count is zero.
        List<Long> sessionIds = jdbc.queryForList(
                "SELECT DISTINCT t.session_id FROM observation_turn t " +
                        "JOIN observation_step st ON st.turn_id=t.id " +
                        "WHERE " + ObservationRepository.invokedSkillStep("st", "?") +
                        "ORDER BY t.session_id DESC LIMIT ?",
                Long.class, slug, Integer.valueOf(maxSessionsForPaths));
        if (sessionIds.isEmpty()) return Collections.emptyList();
        String placeholders = String.join(",", Collections.nCopies(sessionIds.size(), "?"));
        Object[] args = sessionIds.toArray();

        Map<Long, List<String>> sequenceBySession = new LinkedHashMap<Long, List<String>>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.session_id, st.skill_slug FROM observation_step st " +
                        "JOIN observation_turn t ON t.id=st.turn_id " +
                        "WHERE t.session_id IN (" + placeholders + ") " +
                        // A step counts only when its own slug has a published matching version.
                        "  AND " + ObservationRepository.invokedSkillStep("st", "st.skill_slug") +
                        "  AND COALESCE(st.payload->>'rollup','false') <> 'true' " +
                        "ORDER BY t.session_id, t.turn_index, st.seq",
                args);
        for (Map<String, Object> row : rows) {
            Long sessionId = Long.valueOf(((Number) row.get("session_id")).longValue());
            String value = stringOf(row.get("skill_slug"), "");
            if (value.isEmpty()) continue;
            List<String> sequence = sequenceBySession.get(sessionId);
            if (sequence == null) {
                sequence = new ArrayList<String>();
                sequenceBySession.put(sessionId, sequence);
            }
            if (sequence.isEmpty() || !sequence.get(sequence.size() - 1).equals(value)) sequence.add(value);
        }

        Map<Long, Integer> stepsBySession = new HashMap<Long, Integer>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT t.session_id, COUNT(*) AS steps FROM observation_step st " +
                        "JOIN observation_turn t ON t.id=st.turn_id " +
                        "WHERE t.session_id IN (" + placeholders + ") GROUP BY t.session_id", args)) {
            stepsBySession.put(Long.valueOf(((Number) row.get("session_id")).longValue()),
                    Integer.valueOf(intOf(row.get("steps"))));
        }

        Map<Long, Long> tokensBySession = new HashMap<Long, Long>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT t.session_id, COALESCE(SUM(NULLIF(st.payload#>>'{usage,total_tokens}','')::numeric),0) AS tokens " +
                        "FROM observation_step st JOIN observation_turn t ON t.id=st.turn_id " +
                        "WHERE t.session_id IN (" + placeholders + ") GROUP BY t.session_id", args)) {
            tokensBySession.put(Long.valueOf(((Number) row.get("session_id")).longValue()),
                    Long.valueOf(longOf(row.get("tokens"))));
        }

        Map<Long, List<Long>> durationsBySession = new HashMap<Long, List<Long>>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT t.session_id, NULLIF(st.payload->>'duration_ms','')::numeric AS duration_ms " +
                        "FROM observation_step st JOIN observation_turn t ON t.id=st.turn_id " +
                        "WHERE t.session_id IN (" + placeholders + ") " +
                        "  AND st.type='skill' AND NULLIF(st.payload->>'duration_ms','') IS NOT NULL " +
                        "  AND " + ObservationRepository.publishedVersionMatch("st") +
                        "  AND COALESCE(st.payload->>'rollup','false') <> 'true'",
                args)) {
            Long sessionId = Long.valueOf(((Number) row.get("session_id")).longValue());
            long duration = longOf(row.get("duration_ms"));
            if (duration < 0) continue;
            List<Long> bucket = durationsBySession.get(sessionId);
            if (bucket == null) {
                bucket = new ArrayList<Long>();
                durationsBySession.put(sessionId, bucket);
            }
            bucket.add(Long.valueOf(duration));
        }

        Map<String, List<Long>> grouped = new LinkedHashMap<String, List<Long>>();
        for (Long sessionId : sequenceBySession.keySet()) {
            List<String> sequence = sequenceBySession.get(sessionId);
            if (sequence.isEmpty()) continue;
            String signature = join(sequence, " → ");
            List<Long> bucket = grouped.get(signature);
            if (bucket == null) {
                bucket = new ArrayList<Long>();
                grouped.put(signature, bucket);
            }
            bucket.add(sessionId);
        }

        List<Map<String, Object>> paths = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, List<Long>> entry : grouped.entrySet()) {
            List<Long> tokens = new ArrayList<Long>();
            List<Long> steps = new ArrayList<Long>();
            List<Long> durations = new ArrayList<Long>();
            for (Long sessionId : entry.getValue()) {
                tokens.add(tokensBySession.containsKey(sessionId) ? tokensBySession.get(sessionId) : Long.valueOf(0L));
                steps.add(Long.valueOf(stepsBySession.containsKey(sessionId)
                        ? stepsBySession.get(sessionId).longValue() : 0L));
                if (durationsBySession.containsKey(sessionId)) durations.addAll(durationsBySession.get(sessionId));
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("signature", entry.getKey());
            item.put("sessionCount", Integer.valueOf(entry.getValue().size()));
            item.put("medianTokens", Long.valueOf(median(tokens)));
            item.put("medianSteps", Long.valueOf(median(steps)));
            item.put("medianDurationMs", Long.valueOf(median(durations)));
            paths.add(item);
        }
        Collections.sort(paths, new java.util.Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return intOf(b.get("sessionCount")) - intOf(a.get("sessionCount"));
            }
        });
        if (paths.size() > 8) paths = new ArrayList<Map<String, Object>>(paths.subList(0, 8));

        // Cheapest repeated route is the one worth recommending.
        Map<String, Object> cheapest = null;
        for (Map<String, Object> item : paths) {
            if (intOf(item.get("sessionCount")) < 2) continue;
            long tokens = longOf(item.get("medianTokens"));
            if (tokens <= 0) continue;
            if (cheapest == null || tokens < longOf(cheapest.get("medianTokens"))) cheapest = item;
        }
        for (Map<String, Object> item : paths) {
            if (cheapest != null && item == cheapest) {
                item.put("note", "这条路径最省，可作为推荐用法写进技能说明");
            }
        }
        return paths;
    }

    // --------------------------------------------------------- suggestions

    /**
     * Turn findings into concrete edits, phrased for whoever maintains the skill.
     */
    private List<Map<String, Object>> buildSuggestions(List<Map<String, Object>> problems, double coverage,
                                                       List<SkillContract.Step> steps,
                                                       Map<String, Integer> missedCount) {
        List<Map<String, Object>> suggestions = new ArrayList<Map<String, Object>>();
        Map<String, Integer> byRule = new HashMap<String, Integer>();
        for (Map<String, Object> problem : problems) {
            String rule = stringOf(problem.get("ruleId"), "");
            Integer current = byRule.get(rule);
            byRule.put(rule, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }

        if (coverage >= 0 && coverage < coverageWarnThreshold) {
            List<String> worst = new ArrayList<String>();
            for (Map.Entry<String, Integer> entry : missedCount.entrySet()) {
                if (worst.size() < 3) worst.add(titleOfStep(steps, entry.getKey()));
            }
            suggestions.add(suggestion("SKILL.md 的步骤说明", "改写步骤",
                    "技能写完的步骤，实际只做了 " + Math.round(coverage * 100) + "%",
                    "最常被跳过的步骤是：" + join(worst, "、")
                            + "。建议把这些步骤写得更醒目（放到最前面、单独成节，并写清完成标准）。"));
        }
        if (byRule.containsKey("R2")) {
            suggestions.add(suggestion("SKILL.md 的开头", "补充执行要求",
                    "有 " + byRule.get("R2") + " 轮读入了技能却没有动手",
                    "建议在技能开头明确写出「读完本技能后必须做的第一件事」，避免只读不用。"));
        }
        if (byRule.containsKey("R4")) {
            suggestions.add(suggestion("技能适用场景", "收窄触发条件",
                    "有 " + byRule.get("R4") + " 轮和其它技能混在一起",
                    "建议在描述里写清「只在这个技能负责的场景使用」，并在查看「技能冲突」中确认是否需要合并。"));
        }
        if (byRule.containsKey("R7")) {
            suggestions.add(suggestion("缺失的步骤", "补齐或标注可选",
                    "有步骤始终没有执行",
                    "把确实不必每次都做的步骤标成「可选」，其余步骤补上更明确的操作指令。"));
        }
        if (byRule.containsKey("R8")) {
            suggestions.add(suggestion("禁止事项", "补写禁止项",
                    "出现过技能里没提到的额外操作",
                    "建议增加一段「不要做的事」，明确限制越界的动作。"));
        }
        if (byRule.containsKey("R1")) {
            suggestions.add(suggestion("技能依赖", "检查前置条件",
                    "载入技能时有报错",
                    "建议把前置条件（需要的工具、文件、权限、安装步骤）写在技能开头。"));
        }
        if (byRule.containsKey("R3")) {
            suggestions.add(suggestion("技能篇幅", "精简内容",
                    "同一轮里重复载入了该技能",
                    "技能内容偏长时会反复读取，建议拆分或精简。"));
        }
        if (byRule.containsKey("R9")) {
            suggestions.add(suggestion("版本", "先升级再对比",
                    "有人在用旧版本",
                    "建议先让使用者升级到最新版本，再复测；旧版本的问题可能已经修复。"));
        }
        if (suggestions.isEmpty()) {
            suggestions.add(suggestion("整体", "保持现状",
                    "暂未发现明显问题",
                    "本次检查里没有发现明显的执行缺陷。可以继续观察更多使用记录。"));
        }
        return suggestions;
    }

    private Map<String, Object> suggestion(String target, String kind, String title, String detail) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("target", target);
        item.put("kind", kind);
        item.put("title", title);
        item.put("detail", detail.trim());
        return item;
    }

    // --------------------------------------------------------------- load

    private List<TurnData> loadTurns(String slug, String version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id AS turn_id, t.turn_index, t.session_id, c.client_id, t.started_at, t.user_text " +
                        "FROM observation_turn t " +
                        "JOIN observation_session sess ON sess.id=t.session_id " +
                        "JOIN observation_client c ON c.id=sess.client_row_id " +
                        "WHERE EXISTS (SELECT 1 FROM observation_step st WHERE st.turn_id=t.id " +
                        "  AND st.type='skill' AND st.skill_slug=? " +
                        "  AND COALESCE(st.payload->>'rollup','false') <> 'true' " +
                        "  AND LOWER(REGEXP_REPLACE(TRIM(COALESCE(st.skill_version_label,'')),'^[vV]','')) = LOWER(?)) " +
                        "ORDER BY t.started_at DESC NULLS LAST, t.id DESC LIMIT ?",
                slug, version, Integer.valueOf(maxTurns));
        if (rows.isEmpty()) return Collections.emptyList();

        Map<Long, TurnData> byId = new LinkedHashMap<Long, TurnData>();
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> row : rows) {
            TurnData turn = new TurnData();
            turn.turnId = ((Number) row.get("turn_id")).longValue();
            turn.turnIndex = intOf(row.get("turn_index"));
            turn.sessionId = ((Number) row.get("session_id")).longValue();
            turn.clientId = stringOf(row.get("client_id"), "");
            turn.userText = stringOf(row.get("user_text"), "");
            byId.put(Long.valueOf(turn.turnId), turn);
            ids.add(Long.valueOf(turn.turnId));
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Map<String, Object>> stepRows = jdbc.queryForList(
                // version_ok carries the same membership rule down to the step level, so a
                // turn that qualified through one skill cannot be credited to another skill
                // whose observed version was never published.
                "SELECT turn_id, seq, type, skill_slug, payload, " +
                        "  (type <> 'skill' OR " + ObservationRepository.publishedVersionMatch("observation_step") +
                        "  ) AS version_ok " +
                        "FROM observation_step WHERE turn_id IN (" + placeholders + ") ORDER BY turn_id, seq",
                ids.toArray());
        for (Map<String, Object> row : stepRows) {
            TurnData turn = byId.get(Long.valueOf(((Number) row.get("turn_id")).longValue()));
            if (turn == null) continue;
            StepRow step = new StepRow();
            step.seq = intOf(row.get("seq"));
            step.type = stringOf(row.get("type"), "");
            step.slug = stringOf(row.get("skill_slug"), null);
            step.versionOk = !"skill".equals(step.type) || Boolean.TRUE.equals(row.get("version_ok"));
            step.payload = asMap(parseJson(row.get("payload")));
            turn.steps.add(step);
        }
        List<TurnData> turns = new ArrayList<TurnData>(byId.values());
        for (TurnData turn : turns) {
            Collections.sort(turn.steps, new java.util.Comparator<StepRow>() {
                public int compare(StepRow a, StepRow b) { return a.seq - b.seq; }
            });
        }
        return turns;
    }

    private void persist(String slug, String version, List<Map<String, Object>> problems,
                         List<Map<String, Object>> coverageRows, double checklistCoverage) {
        try {
            analyses.replaceAll(slug, version, SkillAnalysisRepository.TYPE_PROBLEM, problems);
            List<Map<String, Object>> coverage = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> row : coverageRows) {
                Map<String, Object> item = new LinkedHashMap<String, Object>(row);
                item.put("scopeKey", String.valueOf(row.get("turnId")));
                coverage.add(item);
            }
            analyses.replaceAll(slug, version, SkillAnalysisRepository.TYPE_COVERAGE, coverage);
        } catch (RuntimeException ignored) {
            // Analysis is a side path: never let caching break the report.
        }
    }

    // -------------------------------------------------------------- helpers

    private List<SkillContract.Step> contractSteps(Map<String, Object> contractView) {
        List<SkillContract.Step> steps = new ArrayList<SkillContract.Step>();
        if (contractView == null) return steps;
        Object contract = contractView.get("contract");
        if (!(contract instanceof Map)) return steps;
        Object rawSteps = ((Map<String, Object>) contract).get("steps");
        if (!(rawSteps instanceof List)) return steps;
        for (Object item : (List<Object>) rawSteps) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> map = (Map<String, Object>) item;
            SkillContract.Step step = new SkillContract.Step();
            step.setId(stringOf(map.get("id"), "s" + (steps.size() + 1)));
            step.setTitle(stringOf(map.get("title"), ""));
            step.setKeywords(stringList(map.get("keywords")));
            step.setExpectedTools(stringList(map.get("expectedTools")));
            step.setExpectedArtifacts(stringList(map.get("expectedArtifacts")));
            step.setOptional(Boolean.TRUE.equals(map.get("optional")));
            Object group = map.get("parallelGroup");
            step.setParallelGroup(group == null ? null : String.valueOf(group));
            if (!step.getTitle().isEmpty()) steps.add(step);
        }
        return steps;
    }

    private Map<String, Object> contractView(Map<String, Object> stored, List<SkillContract.Step> steps) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("hasContract", Boolean.valueOf(!steps.isEmpty()));
        view.put("stepCount", Integer.valueOf(steps.size()));
        if (stored != null) {
            view.put("parseMethod", stored.get("parseMethod"));
            view.put("updatedAt", stored.get("updatedAt"));
            Object meta = stored.get("parseMeta");
            view.put("meta", meta == null ? Collections.emptyMap() : meta);
        } else {
            view.put("parseMethod", "missing");
        }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (SkillContract.Step step : steps) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", step.getId());
            item.put("title", step.getTitle());
            item.put("optional", Boolean.valueOf(step.isOptional()));
            item.put("expectedTools", step.getExpectedTools());
            list.add(item);
        }
        view.put("steps", list);
        return view;
    }

    private List<StepRow> skillStepsOf(TurnData turn, String slug) {
        List<StepRow> steps = new ArrayList<StepRow>();
        for (StepRow step : turn.steps) {
            if (!"skill".equals(step.type)) continue;
            if (!slug.equals(step.slug)) continue;
            if (Boolean.TRUE.equals(step.payload.get("rollup"))) continue;
            if (!step.versionOk) continue;
            steps.add(step);
        }
        return steps;
    }

    private boolean isErrorStep(StepRow step) {
        Object structured = step.payload.get("error");
        if (structured instanceof Boolean) return ((Boolean) structured).booleanValue();
        return "error".equalsIgnoreCase(stringOf(step.payload.get("outcome"), ""));
    }

    /** Distinct skills in a turn with parent/child pairs folded into one identity. */
    private Set<String> conflictKeys(TurnData turn) {
        Set<String> keys = new LinkedHashSet<String>();
        for (StepRow step : turn.steps) {
            if (!"skill".equals(step.type)) continue;
            if (step.slug == null || step.slug.isEmpty()) continue;
            if (Boolean.TRUE.equals(step.payload.get("rollup"))) continue;
            if (!step.versionOk) continue;
            keys.add(conflictKey(step.slug));
        }
        return keys;
    }

    private String conflictKey(String slug) {
        if (slug == null) return "";
        String key = slug.toLowerCase(Locale.ROOT);
        if (key.startsWith("sop-")) return "using-product-development";
        if ("using-superpowers".equals(key)) return "superpowers";
        if (COMPOSITE_PARENTS.contains(key)) return key;
        return key;
    }

    private String actionText(StepRow action) {
        StringBuilder builder = new StringBuilder();
        builder.append(stringOf(action.payload.get("name"), "")).append(' ');
        builder.append(stringOf(action.payload.get("path"), "")).append(' ');
        Object args = action.payload.get("args");
        if (args != null) builder.append(String.valueOf(args)).append(' ');
        Object result = action.payload.get("result");
        if (result != null) builder.append(cap(String.valueOf(result), 600));
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private List<Long> collectTurnTokens(List<TurnData> turns, String slug) {
        List<Long> values = new ArrayList<Long>();
        for (TurnData turn : turns) {
            long tokens = turnTokens(turn, slug);
            if (tokens > 0) values.add(Long.valueOf(tokens));
        }
        return values;
    }

    private long turnTokens(TurnData turn, String slug) {
        long total = 0L;
        for (StepRow step : skillStepsOf(turn, slug)) {
            total += longOf(step.payload.get("usage") instanceof Map
                    ? ((Map<?, ?>) step.payload.get("usage")).get("total_tokens") : null);
        }
        return total;
    }

    private List<Long> collectTurnStepCounts(List<TurnData> turns) {
        List<Long> values = new ArrayList<Long>();
        for (TurnData turn : turns) values.add(Long.valueOf(turn.steps.size()));
        return values;
    }

    private static long percentile(List<Long> values, double q) {
        if (values == null || values.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<Long>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        if (index < 0) index = 0;
        if (index >= sorted.size()) index = sorted.size() - 1;
        return sorted.get(index).longValue();
    }

    private static long median(List<Long> values) {
        if (values == null || values.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<Long>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) return sorted.get(size / 2).longValue();
        return (sorted.get(size / 2 - 1).longValue() + sorted.get(size / 2).longValue()) / 2;
    }

    private int countRequired(List<SkillContract.Step> steps) {
        int count = 0;
        for (SkillContract.Step step : steps) {
            if (!step.isOptional()) count += 1;
        }
        return count;
    }

    private String titleOfStep(List<SkillContract.Step> steps, String id) {
        for (SkillContract.Step step : steps) {
            if (id.equals(step.getId())) return cap(step.getTitle(), 30);
        }
        return id;
    }

    private String coverageLabel(double coverage) {
        if (coverage < 0) return "暂无法评估";
        if (coverage >= 0.9d) return "完整";
        if (coverage >= coverageWarnThreshold) return "基本完整";
        if (coverage >= 0.5d) return "部分执行";
        return "执行不完整";
    }

    private static String join(List<String> items, String separator) {
        StringBuilder builder = new StringBuilder();
        if (items != null) {
            for (String item : items) {
                if (item == null || item.isEmpty()) continue;
                if (builder.length() > 0) builder.append(separator);
                builder.append(item);
            }
        }
        return builder.toString();
    }

    private static String cap(String text, int limit) {
        if (text == null) return null;
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static List<String> nullSafe(List<String> value) {
        return value == null ? Collections.<String>emptyList() : value;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null) out.add(String.valueOf(item));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) return (Map<String, Object>) value;
        return new LinkedHashMap<String, Object>();
    }

    private Object parseJson(Object value) {
        if (value == null) return null;
        String raw;
        if (value instanceof PGobject) {
            raw = ((PGobject) value).getValue();
        } else if (value instanceof byte[]) {
            raw = new String((byte[]) value, StandardCharsets.UTF_8);
        } else {
            raw = String.valueOf(value);
        }
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return mapper.readValue(raw, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stringOf(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isEmpty() ? fallback : text;
    }

    private static int intOf(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static long longOf(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            try {
                return (long) Double.parseDouble(String.valueOf(value).trim());
            } catch (Exception ignoredAgain) {
                return 0L;
            }
        }
    }

    private static double doubleOf(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static class TurnData {
        long turnId;
        int turnIndex;
        long sessionId;
        String clientId;
        String userText;
        List<StepRow> steps = new ArrayList<StepRow>();
    }

    private static class StepRow {
        int seq;
        String type;
        String slug;
        /** 该步骤是否符合成员判定（skill 步骤需版本已发布；其余类型恒为 true）。 */
        boolean versionOk = true;
        Map<String, Object> payload;
    }

    private static class LlmCandidate {
        final TurnData turn;
        final String severity;
        final String pattern;

        LlmCandidate(TurnData turn, String severity, String pattern) {
            this.turn = turn;
            this.severity = severity;
            this.pattern = pattern;
        }
    }

    private void persistAggregates(String slug, String version) {
        try {
            List<Map<String, Object>> conflictRows = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> conflict : allConflicts(slug)) {
                String a = stringOf(conflict.get("a"), "");
                String b = stringOf(conflict.get("b"), "");
                if (a.isEmpty() || b.isEmpty()) continue;
                Map<String, Object> row = new LinkedHashMap<String, Object>(conflict);
                row.put("scopeKey", a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a);
                conflictRows.add(row);
            }
            analyses.replaceAll(slug, version, SkillAnalysisRepository.TYPE_CONFLICT, conflictRows);

            List<Map<String, Object>> pathRows = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> path : pathsFor(slug)) {
                Map<String, Object> row = new LinkedHashMap<String, Object>(path);
                row.put("scopeKey", stringOf(path.get("signature"), ""));
                pathRows.add(row);
            }
            analyses.replaceAll(slug, version, SkillAnalysisRepository.TYPE_PATH, pathRows);
        } catch (RuntimeException ignored) {
            // Aggregates are side-path outputs; persist failure must not break the report.
        }
    }
}
