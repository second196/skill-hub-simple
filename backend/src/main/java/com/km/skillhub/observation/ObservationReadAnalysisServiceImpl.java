package com.km.skillhub.observation;

import com.km.skillhub.skill.SkillVersions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-time analysis implementation.
 *
 * <p>Read-only input, read-only output: no repository/mapper is held and nothing is
 * written back. Skill associations, version display, tool outcomes, token
 * attribution, quality conclusions and the five view models are all computed while
 * reading; legacy stored fields only mark the {@code legacyStored} source and never
 * overwrite raw. Missing usage/reasoning/file_ops are flagged as missing — never
 * fabricated as measured zeros.</p>
 */
@Service
public class ObservationReadAnalysisServiceImpl implements ObservationReadAnalysisService {

    /** Bump to invalidate the read-only analysis cache after rule changes. */
    static final String ANALYSIS_RULE_VERSION = "obs-read-analysis-v1";

    private static final int PREVIEW_TEXT_MAX = 2000;
    private static final int PREVIEW_STEP_MAX = 50;
    private static final long CACHE_TTL_MS = 60_000L;
    private static final int CACHE_MAX_ENTRIES = 256;

    private final ConcurrentHashMap<String, CachedAnalysis> analysisCache =
            new ConcurrentHashMap<String, CachedAnalysis>();

    @Override
    public ObservationReadResponse analyze(Map<String, Object> raw, Map<String, Object> platformCatalog) {
        Map<String, Object> safeRaw = raw == null ? Collections.<String, Object>emptyMap() : raw;
        String cacheKey;
        try {
            cacheKey = buildCacheKey(safeRaw, platformCatalog);
        } catch (RuntimeException ex) {
            // Hostile raw must degrade, never break the read path.
            cacheKey = "unreadable@" + System.identityHashCode(safeRaw);
        }
        long now = System.currentTimeMillis();
        CachedAnalysis cached = analysisCache.get(cacheKey);
        if (cached != null && cached.expiresAt > now) {
            // Copy so callers cannot poison the cache; raw always binds to this call's input.
            return new ObservationReadResponse(safeRaw, copyAnalysis(cached.analysis), cached.status);
        }
        ObservationReadResponse response = compute(safeRaw, platformCatalog);
        if (analysisCache.size() >= CACHE_MAX_ENTRIES) {
            analysisCache.clear();
        }
        analysisCache.put(cacheKey, new CachedAnalysis(
                copyAnalysis(response.getAnalysis()),
                response.getAnalysisStatus(),
                now + CACHE_TTL_MS));
        return response;
    }

    private ObservationReadResponse compute(Map<String, Object> safeRaw, Map<String, Object> platformCatalog) {
        Map<String, Object> analysis = new LinkedHashMap<String, Object>();
        boolean catalogAvailable = isCatalogAvailable(platformCatalog);

        List<StepView> steps;
        try {
            steps = extractSteps(safeRaw);
        } catch (RuntimeException ex) {
            analysis.put("analysisSource", ObservationReadResponse.SOURCE_UNAVAILABLE);
            analysis.put("error", String.valueOf(ex.getMessage()));
            fillEmptyAnalysisSections(analysis);
            try {
                applyCompatMarkers(analysis, Collections.<StepView>emptyList(), safeRaw);
            } catch (RuntimeException ignore) {
                // Compat markers must never mask the degraded result.
            }
            return new ObservationReadResponse(safeRaw, analysis, ObservationReadResponse.STATUS_UNAVAILABLE);
        }

        try {
            boolean legacy = hasLegacyStoredFields(steps);
            buildSkillAssociations(analysis, steps, platformCatalog, catalogAvailable, legacy);
            buildSkillVersions(analysis, steps, platformCatalog, catalogAvailable, legacy);
            buildToolOutcomes(analysis, steps);
            buildTokenAttribution(analysis, steps);
            buildQuality(analysis, steps);
            buildViewModels(analysis, steps, safeRaw);
            applyCompatMarkers(analysis, steps, safeRaw);

            if (legacy) {
                analysis.put("analysisSource", ObservationReadResponse.SOURCE_LEGACY_STORED);
                return new ObservationReadResponse(safeRaw, analysis, ObservationReadResponse.STATUS_LEGACY_STORED);
            }
            if (!catalogAvailable) {
                analysis.put("analysisSource", ObservationReadResponse.SOURCE_UNAVAILABLE);
                return new ObservationReadResponse(safeRaw, analysis, ObservationReadResponse.STATUS_UNAVAILABLE);
            }
            analysis.put("analysisSource", ObservationReadResponse.SOURCE_RUNTIME_COMPUTED);
            return new ObservationReadResponse(safeRaw, analysis, ObservationReadResponse.STATUS_COMPUTED);
        } catch (RuntimeException ex) {
            analysis.put("analysisSource", ObservationReadResponse.SOURCE_PARTIAL);
            analysis.put("error", String.valueOf(ex.getMessage()));
            fillEmptyAnalysisSections(analysis);
            try {
                applyCompatMarkers(analysis, steps, safeRaw);
            } catch (RuntimeException ignore) {
                // Compat markers must never mask the partial result.
            }
            return new ObservationReadResponse(safeRaw, analysis, ObservationReadResponse.STATUS_PARTIAL);
        }
    }

    /**
     * Cache key: session identity + rule version + catalog version, so a rule or
     * catalog change recomputes on the next read.
     */
    static String buildCacheKey(Map<String, Object> raw, Map<String, Object> platformCatalog) {
        return sessionIdOf(raw) + '|' + ANALYSIS_RULE_VERSION + '|' + catalogVersionOf(platformCatalog);
    }

    private static String sessionIdOf(Map<String, Object> raw) {
        if (raw == null) return "unknown";
        Object session = raw.get("session");
        if (session instanceof Map) {
            Object id = ((Map<?, ?>) session).get("id");
            if (id != null) return String.valueOf(id);
            Object key = ((Map<?, ?>) session).get("sessionKey");
            if (key != null) return String.valueOf(key);
        }
        for (String field : new String[] {"sessionId", "sessionKey", "id", "session_key"}) {
            Object value = raw.get(field);
            if (value != null) return String.valueOf(value);
        }
        return "unknown";
    }

    private static String catalogVersionOf(Map<String, Object> platformCatalog) {
        if (platformCatalog == null) return "none";
        Object explicit = platformCatalog.get("catalogVersion");
        if (explicit != null && !String.valueOf(explicit).trim().isEmpty()) {
            return String.valueOf(explicit).trim();
        }
        if (Boolean.FALSE.equals(platformCatalog.get("available"))) return "unavailable";
        Object skills = platformCatalog.get("skills");
        if (!(skills instanceof List)) return "none";
        List<?> list = (List<?>) skills;
        int hash = 17;
        for (Object item : list) {
            if (item instanceof Map) {
                Map<?, ?> skill = (Map<?, ?>) item;
                Object slug = skill.get("slug");
                if (slug == null) slug = skill.get("name");
                hash = 31 * hash + (slug == null ? 0 : String.valueOf(slug).hashCode());
                Object versions = skill.get("versions");
                hash = 31 * hash + (versions == null ? 0 : String.valueOf(versions).hashCode());
                Object label = skill.get("versionLabel");
                hash = 31 * hash + (label == null ? 0 : String.valueOf(label).hashCode());
            } else {
                hash = 31 * hash + (item == null ? 0 : String.valueOf(item).hashCode());
            }
        }
        return "skills:" + list.size() + ":" + Integer.toHexString(hash);
    }

    private static Map<String, Object> copyAnalysis(Map<String, Object> source) {
        if (source == null) return new LinkedHashMap<String, Object>();
        return new LinkedHashMap<String, Object>(source);
    }

    private static boolean isCatalogAvailable(Map<String, Object> platformCatalog) {
        if (platformCatalog == null) return false;
        Object available = platformCatalog.get("available");
        if (Boolean.FALSE.equals(available)) return false;
        return platformCatalog.get("skills") != null;
    }

    private static void fillEmptyAnalysisSections(Map<String, Object> analysis) {
        if (!analysis.containsKey("skillAssociations")) {
            analysis.put("skillAssociations", Collections.emptyList());
        }
        if (!analysis.containsKey("skillVersions")) {
            analysis.put("skillVersions", Collections.emptyList());
        }
        if (!analysis.containsKey("toolOutcomes")) {
            analysis.put("toolOutcomes", Collections.emptyList());
        }
        if (!analysis.containsKey("tokenAttribution")) {
            analysis.put("tokenAttribution", emptyTokens());
        }
        if (!analysis.containsKey("quality")) {
            analysis.put("quality", emptyQuality());
        }
        if (!analysis.containsKey("graph")) {
            analysis.put("graph", emptyGraph());
        }
        if (!analysis.containsKey("waterfall")) {
            analysis.put("waterfall", Collections.emptyList());
        }
        if (!analysis.containsKey("files")) {
            analysis.put("files", Collections.emptyList());
        }
        if (!analysis.containsKey("stats")) {
            analysis.put("stats", emptyStats());
        }
    }

    /**
     * Legacy/performance compat markers: preview, truncation, on-demand detail and
     * missing measured fields. Missing usage/reasoning/file_ops are only flagged.
     */
    private static void applyCompatMarkers(Map<String, Object> analysis,
                                           List<StepView> steps,
                                           Map<String, Object> raw) {
        Set<String> missing = missingMeasuredFields(steps);
        analysis.put("missingMeasuredFields", new ArrayList<String>(missing));
        analysis.put("measuredFieldsComplete", Boolean.valueOf(missing.isEmpty()));

        Object tokenObj = analysis.get("tokenAttribution");
        Map<String, Object> tokens = tokenObj instanceof Map ? asMap(tokenObj) : emptyTokens();
        if (tokens == null) tokens = emptyTokens();
        if (missing.contains("usage")) {
            tokens.put("measured", Boolean.FALSE);
            tokens.put("source", "missing");
        } else {
            tokens.put("measured", Boolean.TRUE);
            tokens.put("source", "observed");
        }
        analysis.put("tokenAttribution", tokens);

        Map<String, Object> preview = new LinkedHashMap<String, Object>();
        preview.put("stepCount", Integer.valueOf(steps.size()));
        preview.put("previewStepCount", Integer.valueOf(Math.min(steps.size(), PREVIEW_STEP_MAX)));
        preview.put("textLimit", Integer.valueOf(PREVIEW_TEXT_MAX));
        List<Map<String, Object>> stepPreviews = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (StepView step : steps) {
            if (index >= PREVIEW_STEP_MAX) break;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("stepId", step.stepId);
            item.put("type", step.type);
            String text = firstText(safePayloadGet(step.payload, "text"),
                    safePayloadGet(step.payload, "name"),
                    safePayloadGet(step.payload, "path"));
            boolean textTruncated = text.length() > PREVIEW_TEXT_MAX;
            item.put("previewText", textTruncated ? text.substring(0, PREVIEW_TEXT_MAX) : text);
            item.put("textTruncated", Boolean.valueOf(textTruncated));
            item.put("hasDetail", Boolean.valueOf(safePayloadGet(step.payload, "result") != null
                    || safePayloadGet(step.payload, "args") != null
                    || safePayloadGet(step.payload, "text") != null));
            stepPreviews.add(item);
            index++;
        }
        preview.put("steps", stepPreviews);
        analysis.put("preview", preview);

        boolean inputTruncated = rawTruncated(raw) || steps.size() >= PREVIEW_STEP_MAX;
        Map<String, Object> truncated = new LinkedHashMap<String, Object>();
        truncated.put("steps", Boolean.valueOf(steps.size() >= PREVIEW_STEP_MAX));
        truncated.put("preview", Boolean.valueOf(inputTruncated));
        truncated.put("reason", inputTruncated
                ? "长会话预览已截断，完整内容按需加载" : null);
        analysis.put("truncated", truncated);

        boolean detailAvailable = false;
        for (StepView step : steps) {
            if (step.stepId != null && !step.stepId.isEmpty()) {
                detailAvailable = true;
                break;
            }
        }
        if (!detailAvailable) {
            detailAvailable = raw != null && raw.get("node") != null;
        }
        analysis.put("detailAvailable", Boolean.valueOf(detailAvailable));

        Map<String, Object> batch = new LinkedHashMap<String, Object>();
        batch.put("strategy", "turn");
        batch.put("preserveOrder", Boolean.TRUE);
        batch.put("loadedSteps", Integer.valueOf(steps.size()));
        analysis.put("batch", batch);
    }

    private static boolean rawTruncated(Map<String, Object> raw) {
        if (raw == null) return false;
        Object flag = raw.get("truncated");
        if (Boolean.TRUE.equals(flag)) return true;
        if (flag instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) flag;
            return Boolean.TRUE.equals(map.get("turns"))
                    || Boolean.TRUE.equals(map.get("nodes"))
                    || Boolean.TRUE.equals(map.get("steps"));
        }
        Object content = raw.get("content");
        if (content instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) content).get("truncated"))) {
            return true;
        }
        return false;
    }

    /** Old data often lacks reasoning/usage/file_ops: record the gap, never infer values. */
    private static Set<String> missingMeasuredFields(List<StepView> steps) {
        boolean hasUsage = false;
        boolean hasReasoning = false;
        boolean hasFileOps = false;
        for (StepView step : steps) {
            Object usage = safePayloadGet(step.payload, "usage");
            if (usage != null && !isEmptyMap(usage)) {
                hasUsage = true;
                Map<String, Object> usageMap = asMap(usage);
                if (usageMap != null && safePayloadGet(usageMap, "reasoning_output_tokens") != null) {
                    hasReasoning = true;
                }
            }
            if ("reasoning".equalsIgnoreCase(step.type)) hasReasoning = true;
            if (safePayloadGet(step.payload, "reasoning") != null) hasReasoning = true;
            if (safePayloadGet(step.payload, "file_ops") != null || safePayloadGet(step.payload, "files") != null) {
                hasFileOps = true;
            }
        }
        Set<String> missing = new LinkedHashSet<String>();
        if (!hasUsage) missing.add("usage");
        if (!hasReasoning) missing.add("reasoning");
        if (!hasFileOps) missing.add("file_ops");
        return missing;
    }

    private static boolean isEmptyMap(Object value) {
        return value instanceof Map && ((Map<?, ?>) value).isEmpty();
    }

    /** Payload reads must never fail the whole compat pass on a hostile map. */
    private static Object safePayloadGet(Map<String, Object> payload, String key) {
        if (payload == null) return null;
        try {
            return payload.get(key);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---------- raw step extraction (never mutates raw) ----------

    private static List<StepView> extractSteps(Map<String, Object> raw) {
        List<StepView> collected = new ArrayList<StepView>();
        collectFromTurns(raw.get("turns"), collected);
        collectFromStepList(raw.get("steps"), collected);
        collectFromNodes(raw.get("nodes"), collected);
        collectFromNodeDetail(raw, collected);
        List<StepView> steps = new ArrayList<StepView>();
        Set<String> seen = new LinkedHashSet<String>();
        for (StepView step : collected) {
            String key = step.stepId == null || step.stepId.isEmpty()
                    ? "anon-" + steps.size() : step.stepId;
            if (!seen.add(key)) continue;
            steps.add(step);
        }
        return steps;
    }

    private static void collectFromTurns(Object turnsObj, List<StepView> steps) {
        if (!(turnsObj instanceof List)) return;
        for (Object turnObj : (List<?>) turnsObj) {
            Map<String, Object> turn = asMap(turnObj);
            if (turn == null) continue;
            collectFromStepList(turn.get("steps"), steps);
        }
    }

    private static void collectFromStepList(Object stepsObj, List<StepView> steps) {
        if (!(stepsObj instanceof List)) return;
        for (Object stepObj : (List<?>) stepsObj) {
            StepView view = toStepView(stepObj);
            if (view != null) steps.add(view);
        }
    }

    private static void collectFromNodes(Object nodesObj, List<StepView> steps) {
        if (!(nodesObj instanceof List)) return;
        for (Object nodeObj : (List<?>) nodesObj) {
            StepView view = toStepView(nodeObj);
            if (view != null) steps.add(view);
        }
    }

    private static void collectFromNodeDetail(Map<String, Object> raw, List<StepView> steps) {
        Object nodeObj = raw.get("node");
        if (nodeObj == null) return;
        Map<String, Object> node = asMap(nodeObj);
        if (node == null) return;
        Map<String, Object> content = asMap(raw.get("content"));
        // Node detail may fill display fields from content — work on a copy so raw stays clean.
        Map<String, Object> payload = new LinkedHashMap<String, Object>(parsePayload(node.get("payload")));
        if (content != null) {
            if (!payload.containsKey("args") && content.get("args") != null) {
                payload.put("args", content.get("args"));
            }
            if (!payload.containsKey("result") && content.get("result") != null) {
                payload.put("result", content.get("result"));
            }
            if (!payload.containsKey("usage") && content.get("usage") != null) {
                payload.put("usage", content.get("usage"));
            }
            if (!payload.containsKey("file_ops") && content.get("files") != null) {
                payload.put("file_ops", content.get("files"));
            }
        }
        String type = firstText(node.get("type"), node.get("kind"), payload.get("type"));
        String stepId = firstText(node.get("stepId"), node.get("step_id"), node.get("nodeId"), node.get("id"));
        String ts = firstText(node.get("ts"), node.get("startedAt"), node.get("started_at"));
        String skillSlug = firstText(
                node.get("skill_slug"), node.get("skillSlug"),
                payload.get("skill_slug"), payload.get("skillSlug"));
        String skillVersionLabel = firstText(
                node.get("skill_version_label"), node.get("skillVersionLabel"),
                payload.get("skill_version_label"), payload.get("skillVersionLabel"));
        steps.add(new StepView(type, stepId, ts, skillSlug, skillVersionLabel, payload));
    }

    private static StepView toStepView(Object stepObj) {
        Map<String, Object> map = asMap(stepObj);
        if (map == null) return null;
        Map<String, Object> payload = parsePayload(map.get("payload"));
        String type = firstText(map.get("type"), payload.get("type"), map.get("kind"), payload.get("kind"));
        String stepId = firstText(map.get("stepId"), map.get("step_id"), map.get("nodeId"), map.get("id"));
        String ts = firstText(map.get("ts"), map.get("startedAt"), map.get("started_at"));
        String skillSlug = firstText(
                map.get("skill_slug"), map.get("skillSlug"),
                payload.get("skill_slug"), payload.get("skillSlug"));
        String skillVersionLabel = firstText(
                map.get("skill_version_label"), map.get("skillVersionLabel"),
                payload.get("skill_version_label"), payload.get("skillVersionLabel"));
        return new StepView(type, stepId, ts, skillSlug, skillVersionLabel, payload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsePayload(Object payloadObj) {
        if (payloadObj instanceof Map) {
            // Read-only reference; analysis never writes it back.
            return (Map<String, Object>) payloadObj;
        }
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) return (Map<String, Object>) value;
        return null;
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value == null) continue;
            String text = String.valueOf(value).trim();
            if (!text.isEmpty() && !"null".equals(text)) return text;
        }
        return "";
    }

    // ---------- legacy detection ----------

    /** Historical skill_slug / match / skill_activations fields mark the legacyStored source. */
    private static boolean hasLegacyStoredFields(List<StepView> steps) {
        for (StepView step : steps) {
            if (!step.skillSlug.isEmpty()) return true;
            if (!step.skillVersionLabel.isEmpty()) return true;
            if (!firstText(step.payload.get("match")).isEmpty()) return true;
            if (!firstText(step.payload.get("skill_slug"), step.payload.get("skillSlug")).isEmpty()) return true;
            if (!firstText(step.payload.get("skill_version_label"), step.payload.get("skillVersionLabel")).isEmpty()) {
                return true;
            }
            if (hasSkillActivations(step.payload)) return true;
        }
        return false;
    }

    private static boolean hasSkillActivations(Map<String, Object> payload) {
        Object activations = payload.get("skill_activations");
        if (activations == null) activations = payload.get("skillActivations");
        if (activations instanceof List) return !((List<?>) activations).isEmpty();
        return activations != null && !firstText(activations).isEmpty();
    }

    // ---------- analysis sections ----------

    private static void buildSkillAssociations(Map<String, Object> analysis,
                                               List<StepView> steps,
                                               Map<String, Object> platformCatalog,
                                               boolean catalogAvailable,
                                               boolean legacy) {
        List<Map<String, Object>> associations = new ArrayList<Map<String, Object>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (StepView step : steps) {
            List<String> slugs = extractSkillSlugs(step);
            for (String slug : slugs) {
                String key = step.stepId + "|" + slug;
                if (!seen.add(key)) continue;
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("stepId", step.stepId);
                item.put("slug", slug);
                item.put("matchType", matchTypeOf(step, slug));
                item.put("source", legacy
                        ? ObservationReadResponse.SOURCE_LEGACY_STORED
                        : ObservationReadResponse.SOURCE_RUNTIME_COMPUTED);
                item.put("inCatalog", catalogAvailable
                        ? Boolean.valueOf(catalogContainsSkill(platformCatalog, slug))
                        : Boolean.FALSE);
                associations.add(item);
            }
        }
        analysis.put("skillAssociations", associations);
    }

    private static List<String> extractSkillSlugs(StepView step) {
        List<String> slugs = new ArrayList<String>();
        addSlug(slugs, step.skillSlug);
        addSlug(slugs, firstText(step.payload.get("skill_slug"), step.payload.get("skillSlug")));
        if ("skill".equalsIgnoreCase(step.type)) {
            addSlug(slugs, firstText(step.payload.get("name")));
        }
        Object activations = step.payload.get("skill_activations");
        if (activations == null) activations = step.payload.get("skillActivations");
        if (activations instanceof List) {
            for (Object activationObj : (List<?>) activations) {
                Map<String, Object> activation = asMap(activationObj);
                if (activation == null) {
                    addSlug(slugs, firstText(activationObj));
                    continue;
                }
                addSlug(slugs, firstText(activation.get("slug"), activation.get("name")));
            }
        }
        if (slugs.isEmpty()) {
            ObservationSkillInference.SkillIdentity identity =
                    ObservationSkillInference.infer(step.type, step.payload);
            if (identity != null) {
                addSlug(slugs, identity.slug);
            }
        }
        return slugs;
    }

    private static void addSlug(List<String> slugs, String slug) {
        if (slug == null || slug.trim().isEmpty()) return;
        String value = slug.trim();
        if (!slugs.contains(value)) slugs.add(value);
    }

    private static String matchTypeOf(StepView step, String slug) {
        if (!step.skillSlug.isEmpty() || !firstText(step.payload.get("skill_slug")).isEmpty()) {
            return "slug";
        }
        if (!firstText(step.payload.get("match")).isEmpty()) {
            return "legacyMatch";
        }
        if (hasSkillActivations(step.payload)) {
            return "skillActivations";
        }
        if ("skill".equalsIgnoreCase(step.type)) {
            return "skillStep";
        }
        return "name";
    }

    private static void buildSkillVersions(Map<String, Object> analysis,
                                           List<StepView> steps,
                                           Map<String, Object> platformCatalog,
                                           boolean catalogAvailable,
                                           boolean legacy) {
        List<Map<String, Object>> versions = new ArrayList<Map<String, Object>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (StepView step : steps) {
            List<String> slugs = extractSkillSlugs(step);
            if (slugs.isEmpty()) {
                slugs.add("");
            }
            String observed = firstText(step.skillVersionLabel,
                    step.payload.get("skill_version_label"), step.payload.get("skillVersionLabel"));
            if (observed.isEmpty() && slugs.size() == 1 && slugs.get(0).isEmpty()) continue;
            for (String slug : slugs) {
                String key = slug + "|" + observed;
                if (!seen.add(key)) continue;
                String normalized = SkillVersions.normalizeVersionLabel(observed);
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("slug", slug);
                item.put("observedLabel", observed.isEmpty() ? null : observed);
                item.put("normalizedLabel", normalized);
                item.put("displayLabel", observed.isEmpty() ? null : observed);
                boolean published = catalogAvailable && normalized != null
                        && catalogContainsVersion(platformCatalog, slug, normalized);
                item.put("published", Boolean.valueOf(published));
                item.put("matchedPublishedLabel", published ? normalized : null);
                item.put("source", legacy
                        ? ObservationReadResponse.SOURCE_LEGACY_STORED
                        : ObservationReadResponse.SOURCE_RUNTIME_COMPUTED);
                versions.add(item);
            }
        }
        analysis.put("skillVersions", versions);
    }

    private static void buildToolOutcomes(Map<String, Object> analysis, List<StepView> steps) {
        List<Map<String, Object>> outcomes = new ArrayList<Map<String, Object>>();
        for (StepView step : steps) {
            boolean isTool = "tool".equalsIgnoreCase(step.type)
                    || step.payload.get("args") != null
                    || step.payload.get("result") != null
                    || step.payload.get("error") != null;
            if (!isTool) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("stepId", step.stepId);
            item.put("toolName", firstText(step.payload.get("name"), step.payload.get("toolName"), step.type));
            item.put("status", toolStatusOf(step));
            item.put("hasResult", Boolean.valueOf(step.payload.get("result") != null));
            item.put("hasError", Boolean.valueOf(step.payload.get("error") != null));
            outcomes.add(item);
        }
        analysis.put("toolOutcomes", outcomes);
    }

    private static String toolStatusOf(StepView step) {
        if (step.payload.get("error") != null) return "error";
        Object outcome = step.payload.get("outcome");
        if (outcome != null) {
            String text = String.valueOf(outcome).toLowerCase(Locale.ROOT);
            if (text.contains("error") || text.contains("fail")) return "error";
            if (text.contains("ok") || text.contains("success") || text.contains("complete")) return "success";
        }
        if (step.payload.get("result") != null) return "success";
        if (step.payload.get("args") != null) return "empty";
        return "unknown";
    }

    private static void buildTokenAttribution(Map<String, Object> analysis, List<StepView> steps) {
        long input = 0L;
        long cacheRead = 0L;
        long cacheWrite = 0L;
        long output = 0L;
        long reasoning = 0L;
        long total = 0L;
        long requests = 0L;
        boolean anyReasoningField = false;
        Map<String, Long> byKind = new LinkedHashMap<String, Long>();
        for (StepView step : steps) {
            Map<String, Object> usage = asMap(step.payload.get("usage"));
            if (usage == null) continue;
            long stepTotal = longOf(usage.get("total_tokens"));
            input += longOf(usage.get("input_tokens"));
            cacheRead += longOf(usage.get("cache_read_input_tokens"));
            cacheWrite += longOf(usage.get("cache_creation_input_tokens"));
            output += longOf(usage.get("output_tokens"));
            if (usage.get("reasoning_output_tokens") != null) anyReasoningField = true;
            reasoning += longOf(usage.get("reasoning_output_tokens"));
            total += stepTotal;
            requests += 1L;
            String kind = step.type == null || step.type.isEmpty() ? "other" : step.type;
            Long previous = byKind.get(kind);
            byKind.put(kind, Long.valueOf((previous == null ? 0L : previous.longValue()) + stepTotal));
        }
        Map<String, Object> tokens = emptyTokens();
        tokens.put("inputTokens", Long.valueOf(input));
        tokens.put("cacheReadTokens", Long.valueOf(cacheRead));
        tokens.put("cacheWriteTokens", Long.valueOf(cacheWrite));
        tokens.put("outputTokens", Long.valueOf(output));
        tokens.put("reasoningTokens", Long.valueOf(reasoning));
        tokens.put("totalTokens", Long.valueOf(total));
        tokens.put("requestCount", Long.valueOf(requests));
        tokens.put("byKind", byKind);
        tokens.put("reasoningMeasured", Boolean.valueOf(anyReasoningField || reasoning > 0L));
        analysis.put("tokenAttribution", tokens);
    }

    private static void buildQuality(Map<String, Object> analysis, List<StepView> steps) {
        int errorCount = 0;
        int emptyResultCount = 0;
        int redactedCount = 0;
        int truncatedCount = 0;
        for (StepView step : steps) {
            String toolStatus = toolStatusOf(step);
            if ("error".equals(toolStatus)) errorCount++;
            if ("empty".equals(toolStatus)) emptyResultCount++;
            if (Boolean.TRUE.equals(step.payload.get("redacted"))) redactedCount++;
            if (Boolean.TRUE.equals(step.payload.get("truncated"))) truncatedCount++;
        }
        Map<String, Object> quality = emptyQuality();
        quality.put("errorCount", Integer.valueOf(errorCount));
        quality.put("emptyResultCount", Integer.valueOf(emptyResultCount));
        quality.put("redactedCount", Integer.valueOf(redactedCount));
        quality.put("truncatedCount", Integer.valueOf(truncatedCount));
        String conclusion = "ok";
        if (errorCount > 0) {
            conclusion = "hasErrors";
        } else if (emptyResultCount > 0 || truncatedCount > 0) {
            conclusion = "incomplete";
        } else if (redactedCount > 0) {
            conclusion = "limited";
        }
        quality.put("conclusion", conclusion);
        analysis.put("quality", quality);
    }

    /** Graph / waterfall / files / stats view models derived from the step list. */
    private static void buildViewModels(Map<String, Object> analysis, List<StepView> steps, Map<String, Object> raw) {
        List<Map<String, Object>> graphNodes = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> graphEdges = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> waterfall = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> files = new LinkedHashMap<String, Map<String, Object>>();
        long totalTokens = 0L;
        int toolCalls = 0;
        int skillCalls = 0;
        String previousId = null;
        for (StepView step : steps) {
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("id", step.stepId);
            node.put("kind", step.type);
            node.put("label", firstText(step.payload.get("name"), step.payload.get("text"), step.stepId));
            List<String> slugs = extractSkillSlugs(step);
            node.put("skillSlug", slugs.isEmpty() ? null : slugs.get(0));
            graphNodes.add(node);
            if (previousId != null) {
                Map<String, Object> edge = new LinkedHashMap<String, Object>();
                edge.put("from", previousId);
                edge.put("to", step.stepId);
                edge.put("type", "sequence");
                graphEdges.add(edge);
            }
            previousId = step.stepId;

            Map<String, Object> fall = new LinkedHashMap<String, Object>();
            fall.put("id", step.stepId);
            fall.put("kind", step.type);
            fall.put("ts", step.ts);
            fall.put("label", node.get("label"));
            waterfall.add(fall);

            if ("tool".equalsIgnoreCase(step.type)) toolCalls++;
            if ("skill".equalsIgnoreCase(step.type) || !slugs.isEmpty()) skillCalls++;
            Map<String, Object> usage = asMap(step.payload.get("usage"));
            if (usage != null) totalTokens += longOf(usage.get("total_tokens"));
            collectFiles(files, step);
        }
        Map<String, Object> graph = emptyGraph();
        graph.put("nodes", graphNodes);
        graph.put("edges", graphEdges);
        analysis.put("graph", graph);
        analysis.put("waterfall", waterfall);
        analysis.put("files", new ArrayList<Map<String, Object>>(files.values()));
        Map<String, Object> stats = emptyStats();
        stats.put("stepCount", Integer.valueOf(steps.size()));
        stats.put("toolCallCount", Integer.valueOf(toolCalls));
        stats.put("skillCallCount", Integer.valueOf(skillCalls));
        stats.put("totalTokens", Long.valueOf(totalTokens));
        analysis.put("stats", stats);
    }

    private static void collectFiles(Map<String, Map<String, Object>> files, StepView step) {
        Object ops = step.payload.get("file_ops");
        if (ops == null) ops = step.payload.get("files");
        if (!(ops instanceof List)) return;
        for (Object opObj : (List<?>) ops) {
            Map<String, Object> op = asMap(opObj);
            if (op == null) continue;
            String path = firstText(op.get("path"));
            if (path.isEmpty()) continue;
            Map<String, Object> row = files.get(path);
            if (row == null) {
                row = new LinkedHashMap<String, Object>();
                row.put("path", path);
                row.put("reads", Integer.valueOf(0));
                row.put("writes", Integer.valueOf(0));
                row.put("touches", Integer.valueOf(0));
                files.put(path, row);
            }
            row.put("touches", Integer.valueOf(intOf(row.get("touches")) + 1));
            String action = firstText(op.get("op"), op.get("kind")).toLowerCase(Locale.ROOT);
            if (action.contains("write") || action.contains("edit") || action.contains("create")) {
                row.put("writes", Integer.valueOf(intOf(row.get("writes")) + 1));
            } else if (action.contains("read")) {
                row.put("reads", Integer.valueOf(intOf(row.get("reads")) + 1));
            }
        }
    }

    private static boolean catalogContainsSkill(Map<String, Object> platformCatalog, String slug) {
        if (slug == null || slug.isEmpty()) return false;
        for (Map<String, Object> skill : catalogSkills(platformCatalog)) {
            String candidate = firstText(skill.get("slug"), skill.get("name"));
            if (candidate.equalsIgnoreCase(slug)) return true;
        }
        return false;
    }

    private static boolean catalogContainsVersion(Map<String, Object> platformCatalog,
                                                  String slug,
                                                  String normalizedLabel) {
        if (normalizedLabel == null || normalizedLabel.isEmpty()) return false;
        for (Map<String, Object> skill : catalogSkills(platformCatalog)) {
            String candidate = firstText(skill.get("slug"), skill.get("name"));
            if (!slug.isEmpty() && !candidate.equalsIgnoreCase(slug)) continue;
            Object versions = skill.get("versions");
            if (versions instanceof List) {
                for (Object versionObj : (List<?>) versions) {
                    String normalized = SkillVersions.normalizeVersionLabel(
                            versionObj == null ? null : String.valueOf(versionObj));
                    if (normalizedLabel.equalsIgnoreCase(normalized)) return true;
                }
            }
            Object label = skill.get("versionLabel");
            String normalized = SkillVersions.normalizeVersionLabel(
                    label == null ? null : String.valueOf(label));
            if (normalizedLabel.equalsIgnoreCase(normalized)) return true;
        }
        return false;
    }

    private static List<Map<String, Object>> catalogSkills(Map<String, Object> platformCatalog) {
        if (platformCatalog == null) return Collections.emptyList();
        Object skills = platformCatalog.get("skills");
        if (!(skills instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object skillObj : (List<?>) skills) {
            Map<String, Object> skill = asMap(skillObj);
            if (skill != null) result.add(skill);
        }
        return result;
    }

    private static Map<String, Object> emptyTokens() {
        Map<String, Object> tokens = new LinkedHashMap<String, Object>();
        tokens.put("inputTokens", Long.valueOf(0L));
        tokens.put("cacheReadTokens", Long.valueOf(0L));
        tokens.put("cacheWriteTokens", Long.valueOf(0L));
        tokens.put("outputTokens", Long.valueOf(0L));
        tokens.put("reasoningTokens", Long.valueOf(0L));
        tokens.put("totalTokens", Long.valueOf(0L));
        tokens.put("requestCount", Long.valueOf(0L));
        tokens.put("byKind", Collections.emptyMap());
        return tokens;
    }

    private static Map<String, Object> emptyQuality() {
        Map<String, Object> quality = new LinkedHashMap<String, Object>();
        quality.put("errorCount", Integer.valueOf(0));
        quality.put("emptyResultCount", Integer.valueOf(0));
        quality.put("redactedCount", Integer.valueOf(0));
        quality.put("truncatedCount", Integer.valueOf(0));
        quality.put("conclusion", "ok");
        return quality;
    }

    private static Map<String, Object> emptyGraph() {
        Map<String, Object> graph = new LinkedHashMap<String, Object>();
        graph.put("nodes", Collections.emptyList());
        graph.put("edges", Collections.emptyList());
        return graph;
    }

    private static Map<String, Object> emptyStats() {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("stepCount", Integer.valueOf(0));
        stats.put("toolCallCount", Integer.valueOf(0));
        stats.put("skillCallCount", Integer.valueOf(0));
        stats.put("totalTokens", Long.valueOf(0L));
        return stats;
    }

    private static long longOf(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static int intOf(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static final class CachedAnalysis {
        private final Map<String, Object> analysis;
        private final String status;
        private final long expiresAt;

        private CachedAnalysis(Map<String, Object> analysis, String status, long expiresAt) {
            this.analysis = analysis;
            this.status = status;
            this.expiresAt = expiresAt;
        }
    }

    /** One raw step view; payload may be the raw reference, never written to. */
    private static final class StepView {
        private final String type;
        private final String stepId;
        private final String ts;
        private final String skillSlug;
        private final String skillVersionLabel;
        private final Map<String, Object> payload;

        private StepView(String type, String stepId, String ts, String skillSlug,
                         String skillVersionLabel, Map<String, Object> payload) {
            this.type = type;
            this.stepId = stepId;
            this.ts = ts;
            this.skillSlug = skillSlug;
            this.skillVersionLabel = skillVersionLabel;
            this.payload = payload;
        }
    }
}
