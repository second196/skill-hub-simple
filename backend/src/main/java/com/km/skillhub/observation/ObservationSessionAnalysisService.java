package com.km.skillhub.observation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.analysis.ObservationAnalyzer;
import com.km.skillhub.skill.SkillVersions;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Session-scoped skill analysis: the same rule/coverage model as the skill-wide
 * report, but computed only over one observation session's turns.
 *
 * <p>Read-only: the contract is reused through {@link ObservationAnalyzer#ensureContract}
 * (stored contracts are immutable per published version) and no analysis result is
 * persisted from here, so session reads never write back observation data.</p>
 */
@Service
public class ObservationSessionAnalysisService {

    private static final int MAX_TURNS = 200;
    private static final int MAX_STEPS_PER_TURN = 500;

    private final JdbcTemplate jdbc;
    private final ObservationAnalyzer analyzer;
    private final ObjectMapper mapper;

    public ObservationSessionAnalysisService(JdbcTemplate jdbc,
                                             ObservationAnalyzer analyzer, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.analyzer = analyzer;
        this.mapper = mapper;
    }

    /**
     * @param sessionId    observation session row id
     * @param slug         skill slug (required, mirrors kmplm contract)
     * @param versionLabel explicit version label; blank = newest published version
     * @param refresh      force contract re-parse when the skill files changed
     */
    public Map<String, Object> analyze(long sessionId, String slug, String versionLabel, boolean refresh) {
        if (slug == null || slug.trim().isEmpty()) {
            throw new IllegalArgumentException("skill 参数不能为空");
        }
        String skill = slug.trim();
        Map<String, Object> session = findSession(sessionId);
        if (session == null) throw new IllegalArgumentException("观测会话不存在");

        String version = resolveVersionLabel(skill, versionLabel);
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("sessionId", Long.valueOf(sessionId));
        report.put("slug", skill);
        report.put("versionLabel", version == null ? "" : version);
        report.put("analysisScope", "session");
        report.put("sessionKey", session.get("session_key"));
        if (version == null) {
            report.put("degraded", Boolean.TRUE);
            report.put("message", "该技能在平台上还没有已发布的版本，暂时无法分析");
            report.put("problems", Collections.emptyList());
            report.put("coverage", Collections.emptyMap());
            report.put("summary", emptySummary());
            return report;
        }

        Map<String, Object> storedContract = null;
        try {
            storedContract = analyzer.ensureContract(skill, version, refresh);
        } catch (RuntimeException ignored) {
            storedContract = null;
        }
        List<ContractStep> contractSteps = contractSteps(storedContract);
        report.put("contract", contractView(storedContract, contractSteps));

        List<TurnData> turns = loadSessionTurns(sessionId, skill, version);
        List<Map<String, Object>> problems = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> coverageRows = new ArrayList<Map<String, Object>>();
        Map<String, Integer> missedCount = new LinkedHashMap<String, Integer>();
        int deviationTotal = 0;
        int coveredTurns = 0;
        double coverageSum = 0d;

        for (TurnData turn : turns) {
            List<StepRow> skillSteps = skillStepsOf(turn, skill, version);
            if (skillSteps.isEmpty()) continue;

            for (StepRow step : skillSteps) {
                if (isErrorStep(step)) {
                    problems.add(problem("R1", "载入报错", "P0", turn,
                            "载入技能时报错，后面的步骤没能继续"));
                    break;
                }
            }

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

            if (skillSteps.size() >= 2) {
                problems.add(problem("R3", "重复载入", "P2", turn,
                        "同一轮对话里重复载入了该技能 " + skillSteps.size() + " 次"));
            }

            Set<String> distinct = conflictKeys(turn);
            if (distinct.size() >= 2) {
                List<String> names = new ArrayList<String>(distinct);
                problems.add(problem("R4", "多个技能混用", "P1", turn,
                        "同一轮对话里同时用到了 " + names.size() + " 个技能：" + join(names, "、")));
            }

            if (!contractSteps.isEmpty()) {
                Map<String, Object> row = coverageOf(turn, skillSteps, contractSteps, missedCount);
                row.put("turnId", Long.valueOf(turn.turnId));
                row.put("turnIndex", Integer.valueOf(turn.turnIndex));
                coverageRows.add(row);
                coveredTurns += 1;
                coverageSum += doubleOf(row.get("checklistCoverage"));
                deviationTotal += intOf(row.get("deviationCount"));
                if (Boolean.TRUE.equals(row.get("orderViolation"))) {
                    problems.add(problem("R6", "步骤顺序不一致", "P1", turn,
                            "实际执行顺序和技能里写的顺序不一样"));
                }
                @SuppressWarnings("unchecked")
                List<String> missing = (List<String>) row.get("missedTitles");
                if (missing != null && !missing.isEmpty()) {
                    problems.add(problem("R7", "步骤缺失", "P1", turn,
                            "没有执行技能要求的步骤：" + join(missing, "、")));
                }
                if (intOf(row.get("deviationCount")) > 0) {
                    problems.add(problem("R8", "出现了额外操作", "P2", turn,
                            "做了 " + row.get("deviationCount") + " 个技能里没有提到的操作"));
                }
            }
        }

        double checklistCoverage = coveredTurns == 0 ? -1d : round2(coverageSum / coveredTurns);
        report.put("summary", buildSummary(turns, problems, checklistCoverage, coveredTurns, deviationTotal));
        report.put("problems", problems);
        report.put("coverage", buildCoverage(checklistCoverage, contractSteps, missedCount, coverageRows));
        report.put("suggestions", buildSuggestions(problems, checklistCoverage));
        return report;
    }

    private Map<String, Object> findSession(long sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, session_key, client_name FROM observation_session WHERE id=?",
                sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Blank version → newest published version_label for the skill. */
    private String resolveVersionLabel(String slug, String versionLabel) {
        if (versionLabel != null && !versionLabel.trim().isEmpty()) {
            return SkillVersions.normalizeVersionLabel(versionLabel);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT v.version_label FROM skill s" +
                        " INNER JOIN skill_version v ON v.skill_id = s.id" +
                        " WHERE s.slug = ? ORDER BY v.created_at DESC, v.id DESC LIMIT 1",
                slug);
        if (rows.isEmpty()) return null;
        Object label = rows.get(0).get("version_label");
        return label == null ? null : SkillVersions.normalizeVersionLabel(String.valueOf(label));
    }

    // ------------------------------------------------------------- session turns

    private static final class TurnData {
        long turnId;
        int turnIndex;
        long sessionId;
        String clientId;
        String userText;
        final List<StepRow> steps = new ArrayList<StepRow>();
    }

    private static final class StepRow {
        String type;
        int seq;
        String slug;
        String versionLabel;
        Map<String, Object> payload = Collections.emptyMap();
    }

    private List<TurnData> loadSessionTurns(long sessionId, String slug, String version) {
        ObservationRepository.VersionFilter vf = ObservationRepository.VersionFilter.of(version);
        String membership = ObservationRepository.skillStepMembership("st", vf);
        List<Object> args = new ArrayList<Object>();
        args.add(Long.valueOf(sessionId));
        args.add(slug);
        for (Object extra : vf.args) args.add(extra);
        List<Map<String, Object>> turnRows = jdbc.queryForList(
                "SELECT t.id, t.turn_index, t.session_id, t.user_text, c.client_id" +
                        " FROM observation_turn t" +
                        " INNER JOIN observation_session sess ON sess.id = t.session_id" +
                        " INNER JOIN observation_client c ON c.id = sess.client_row_id" +
                        " WHERE t.session_id = ?" +
                        " AND EXISTS (SELECT 1 FROM observation_step st WHERE st.turn_id = t.id AND " + membership + ")" +
                        " ORDER BY t.turn_index ASC LIMIT " + MAX_TURNS,
                args.toArray());
        if (turnRows.isEmpty()) return new ArrayList<TurnData>();

        List<Long> turnIds = new ArrayList<Long>();
        Map<Long, TurnData> byId = new LinkedHashMap<Long, TurnData>();
        for (Map<String, Object> row : turnRows) {
            TurnData turn = new TurnData();
            turn.turnId = ((Number) row.get("id")).longValue();
            turn.turnIndex = intOf(row.get("turn_index"));
            turn.sessionId = ((Number) row.get("session_id")).longValue();
            turn.clientId = row.get("client_id") == null ? "" : String.valueOf(row.get("client_id"));
            turn.userText = row.get("user_text") == null ? "" : String.valueOf(row.get("user_text"));
            turnIds.add(Long.valueOf(turn.turnId));
            byId.put(Long.valueOf(turn.turnId), turn);
        }
        StringBuilder placeholders = new StringBuilder();
        List<Object> stepArgs = new ArrayList<Object>();
        for (int i = 0; i < turnIds.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
            stepArgs.add(turnIds.get(i));
        }
        List<Map<String, Object>> stepRows = jdbc.queryForList(
                "SELECT st.turn_id, st.seq, st.type, st.skill_slug, st.skill_version_label, st.payload" +
                        " FROM observation_step st WHERE st.turn_id IN (" + placeholders + ")" +
                        " ORDER BY st.turn_id ASC, st.seq ASC, st.id ASC LIMIT " + (MAX_STEPS_PER_TURN * turnIds.size()),
                stepArgs.toArray());
        for (Map<String, Object> row : stepRows) {
            TurnData turn = byId.get(Long.valueOf(((Number) row.get("turn_id")).longValue()));
            if (turn == null) continue;
            StepRow step = new StepRow();
            step.type = stringOf(row.get("type"));
            step.seq = intOf(row.get("seq"));
            step.slug = row.get("skill_slug") == null ? null : String.valueOf(row.get("skill_slug"));
            step.versionLabel = row.get("skill_version_label") == null
                    ? null : String.valueOf(row.get("skill_version_label"));
            step.payload = asMap(parseJson(row.get("payload")));
            turn.steps.add(step);
        }
        List<TurnData> ordered = new ArrayList<TurnData>();
        for (Long turnId : turnIds) {
            TurnData turn = byId.get(turnId);
            if (turn != null) ordered.add(turn);
        }
        return ordered;
    }

    // --------------------------------------------------------------------- rules

    private static List<StepRow> skillStepsOf(TurnData turn, String slug, String version) {
        List<StepRow> result = new ArrayList<StepRow>();
        for (StepRow step : turn.steps) {
            if (!"skill".equals(step.type)) continue;
            if (!slug.equals(step.slug)) continue;
            if (Boolean.TRUE.equals(step.payload.get("rollup"))) continue;
            String normalized = SkillVersions.normalizeVersionLabel(step.versionLabel);
            if (normalized == null || !normalized.equalsIgnoreCase(version)) continue;
            result.add(step);
        }
        return result;
    }

    private static boolean isErrorStep(StepRow step) {
        Object structured = step.payload.get("error");
        if (structured instanceof Boolean) return ((Boolean) structured).booleanValue();
        Object outcome = step.payload.get("outcome");
        return outcome != null && "error".equalsIgnoreCase(String.valueOf(outcome));
    }

    /** Distinct skill identities used inside one turn (for the mixed-skills rule). */
    private static Set<String> conflictKeys(TurnData turn) {
        Set<String> keys = new LinkedHashSet<String>();
        for (StepRow step : turn.steps) {
            if (!"skill".equals(step.type)) continue;
            if (Boolean.TRUE.equals(step.payload.get("rollup"))) continue;
            String slug = step.slug;
            if (slug == null || slug.trim().isEmpty()) {
                slug = stringOf(step.payload.get("skill_slug"));
            }
            if (slug.trim().isEmpty()) slug = stringOf(step.payload.get("name"));
            if (!slug.trim().isEmpty()) keys.add(slug.trim());
        }
        return keys;
    }

    private Map<String, Object> problem(String ruleId, String title, String severity,
                                        TurnData turn, String detail) {
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

    // ------------------------------------------------------------------ coverage

    private static final class ContractStep {
        String id;
        String title;
        List<String> keywords = Collections.emptyList();
        List<String> expectedTools = Collections.emptyList();
        List<String> expectedArtifacts = Collections.emptyList();
        boolean optional;
        String parallelGroup;
    }

    @SuppressWarnings("unchecked")
    private List<ContractStep> contractSteps(Map<String, Object> storedContract) {
        List<ContractStep> steps = new ArrayList<ContractStep>();
        if (storedContract == null) return steps;
        Object contract = storedContract.get("contract");
        if (!(contract instanceof Map)) return steps;
        Object rawSteps = ((Map<String, Object>) contract).get("steps");
        if (!(rawSteps instanceof List)) return steps;
        for (Object item : (List<Object>) rawSteps) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> map = (Map<String, Object>) item;
            ContractStep step = new ContractStep();
            step.id = stringOf(map.get("id"), "s" + (steps.size() + 1));
            step.title = stringOf(map.get("title"));
            step.keywords = stringList(map.get("keywords"));
            step.expectedTools = stringList(map.get("expectedTools"));
            step.expectedArtifacts = stringList(map.get("expectedArtifacts"));
            step.optional = Boolean.TRUE.equals(map.get("optional"));
            Object group = map.get("parallelGroup");
            step.parallelGroup = group == null ? null : String.valueOf(group);
            if (!step.title.isEmpty()) steps.add(step);
        }
        return steps;
    }

    private static Map<String, Object> contractView(Map<String, Object> stored, List<ContractStep> steps) {
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
        for (ContractStep step : steps) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", step.id);
            item.put("title", step.title);
            item.put("optional", Boolean.valueOf(step.optional));
            item.put("expectedTools", step.expectedTools);
            list.add(item);
        }
        view.put("steps", list);
        return view;
    }

    /** Match one turn's real actions against the skill's expected steps. */
    private static Map<String, Object> coverageOf(TurnData turn, List<StepRow> skillSteps,
                                                  List<ContractStep> steps,
                                                  Map<String, Integer> missedCount) {
        int firstSeq = skillSteps.isEmpty() ? 0 : skillSteps.get(0).seq;
        List<StepRow> actions = new ArrayList<StepRow>();
        for (StepRow step : turn.steps) {
            if (step.seq > firstSeq && ("tool".equals(step.type) || "document".equals(step.type))) {
                actions.add(step);
            }
        }

        Map<String, Integer> matchedOrder = new LinkedHashMap<String, Integer>();
        Set<Integer> usedActions = new LinkedHashSet<Integer>();
        int matchedRequired = 0;
        int required = 0;
        List<String> missedTitles = new ArrayList<String>();
        for (ContractStep step : steps) {
            if (step.optional) continue;
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
                matchedOrder.put(step.id, Integer.valueOf(hit));
            } else {
                missedTitles.add(cap(step.title, 40));
                Integer current = missedCount.get(step.id);
                missedCount.put(step.id, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
            }
        }

        int deviations = actions.size() - usedActions.size();

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("checklistCoverage", required == 0 ? Double.valueOf(-1d) : Double.valueOf(round2((double) matchedRequired / required)));
        row.put("matchedCount", Integer.valueOf(matchedRequired));
        row.put("requiredCount", Integer.valueOf(required));
        row.put("missedTitles", missedTitles);
        row.put("deviationCount", Integer.valueOf(deviations));
        row.put("orderViolation", Boolean.valueOf(isOrderViolation(steps, matchedOrder)));
        return row;
    }

    private static boolean matches(ContractStep step, StepRow action) {
        String text = actionText(action);
        if ("document".equals(action.type)) {
            String path = stringOf(action.payload.get("path"));
            for (String artifact : step.expectedArtifacts) {
                if (matchesArtifact(artifact, path) || matchesArtifact(artifact, text)) return true;
            }
        } else {
            String tool = stringOf(action.payload.get("name"));
            for (String expected : step.expectedTools) {
                if (expected.equalsIgnoreCase(tool)) return true;
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : step.keywords) {
            if (keyword.length() >= 3 && lower.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean matchesArtifact(String pattern, String text) {
        if (pattern == null || pattern.isEmpty() || text == null || text.isEmpty()) return false;
        return text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private static String actionText(StepRow action) {
        StringBuilder sb = new StringBuilder();
        appendText(sb, action.payload.get("text"));
        appendText(sb, action.payload.get("result"));
        appendText(sb, action.payload.get("args"));
        appendText(sb, action.payload.get("path"));
        appendText(sb, action.payload.get("name"));
        return sb.toString();
    }

    private static void appendText(StringBuilder sb, Object value) {
        if (value == null) return;
        if (value instanceof Map) {
            sb.append(' ').append(((Map<?, ?>) value).values());
        } else {
            sb.append(' ').append(value);
        }
    }

    /** Ordered steps must appear in order unless they share a parallel group. */
    private static boolean isOrderViolation(List<ContractStep> steps, Map<String, Integer> matchedOrder) {
        List<String> ordered = new ArrayList<String>();
        for (ContractStep step : steps) {
            if (step.optional || step.parallelGroup != null) continue;
            if (matchedOrder.containsKey(step.id)) ordered.add(step.id);
        }
        int last = -1;
        for (String id : ordered) {
            int current = matchedOrder.get(id).intValue();
            if (current < last) return true;
            last = current;
        }
        return false;
    }

    // ------------------------------------------------------------------- summary

    private static Map<String, Object> buildSummary(List<TurnData> turns, List<Map<String, Object>> problems,
                                                    double checklistCoverage, int coveredTurns, int deviationTotal) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        int problemTurns = 0;
        Set<Long> seen = new LinkedHashSet<Long>();
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
        summary.put("coverageLabel", ObservationRepository.coverageLabel(checklistCoverage));
        summary.put("analysisScope", "session");
        return summary;
    }

    private static Map<String, Object> emptySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("turnsAnalyzed", Integer.valueOf(0));
        summary.put("problemTurnCount", Integer.valueOf(0));
        summary.put("problemCount", Integer.valueOf(0));
        summary.put("coverageTurns", Integer.valueOf(0));
        summary.put("deviationCount", Integer.valueOf(0));
        summary.put("checklistCoverage", Double.valueOf(-1d));
        summary.put("coverageLabel", ObservationRepository.coverageLabel(-1d));
        summary.put("analysisScope", "session");
        return summary;
    }

    private static Map<String, Object> buildCoverage(double checklistCoverage, List<ContractStep> steps,
                                                     Map<String, Integer> missedCount,
                                                     List<Map<String, Object>> rows) {
        Map<String, Object> coverage = new LinkedHashMap<String, Object>();
        coverage.put("checklistCoverage", Double.valueOf(checklistCoverage));
        coverage.put("label", ObservationRepository.coverageLabel(checklistCoverage));
        coverage.put("hasContract", Boolean.valueOf(!steps.isEmpty()));
        int required = 0;
        for (ContractStep step : steps) {
            if (!step.optional) required += 1;
        }
        coverage.put("requiredStepCount", Integer.valueOf(required));

        List<Map<String, Object>> ranked = new ArrayList<Map<String, Object>>();
        for (ContractStep step : steps) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", step.id);
            item.put("title", step.title);
            item.put("optional", Boolean.valueOf(step.optional));
            item.put("missCount", missedCount.containsKey(step.id)
                    ? missedCount.get(step.id) : Integer.valueOf(0));
            ranked.add(item);
        }
        Collections.sort(ranked, new java.util.Comparator<Map<String, Object>>() {
            @Override
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
        coverage.put("turns", rows.size() > 40 ? new ArrayList<Map<String, Object>>(rows.subList(0, 40)) : rows);
        return coverage;
    }

    private static List<Map<String, Object>> buildSuggestions(List<Map<String, Object>> problems,
                                                              double checklistCoverage) {
        List<Map<String, Object>> suggestions = new ArrayList<Map<String, Object>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Map<String, Object> problem : problems) {
            String ruleId = stringOf(problem.get("ruleId"));
            if (!seen.add(ruleId)) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("ruleId", ruleId);
            item.put("title", problem.get("title"));
            item.put("severity", problem.get("severity"));
            item.put("detail", problem.get("detail"));
            suggestions.add(item);
        }
        if (checklistCoverage >= 0 && checklistCoverage < 0.8d && !seen.contains("R7")) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("ruleId", "C1");
            item.put("title", "对照清单补齐步骤");
            item.put("severity", "P1");
            item.put("detail", "该会话的技能步骤完整度为 " + checklistCoverage + "，建议补齐缺失步骤");
            suggestions.add(item);
        }
        return suggestions;
    }

    // -------------------------------------------------------------------- helpers

    private Object parseJson(Object value) {
        if (value == null) return Collections.emptyMap();
        if (value instanceof Map) return value;
        String raw = value instanceof PGobject ? ((PGobject) value).getValue() : String.valueOf(value);
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyMap();
        try {
            return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) return (Map<String, Object>) value;
        return new LinkedHashMap<String, Object>();
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String stringOf(Object value, String fallback) {
        String text = stringOf(value);
        return text.isEmpty() ? fallback : text;
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (!(value instanceof List)) return result;
        for (Object item : (List<?>) value) {
            if (item == null) continue;
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) result.add(text);
        }
        return result;
    }

    private static String cap(String value, int max) {
        if (value == null) return "";
        String text = value.replaceAll("\\s+", " ").trim();
        if (text.length() <= max) return text;
        return text.substring(0, max) + "...";
    }

    private static String join(List<String> names, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    private static int intOf(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static double doubleOf(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null) return 0d;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (RuntimeException ex) {
            return 0d;
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}

