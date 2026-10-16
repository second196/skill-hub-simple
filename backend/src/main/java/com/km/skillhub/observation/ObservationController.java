package com.km.skillhub.observation;

import com.km.skillhub.analysis.ObservationAnalyzer;
import com.km.skillhub.analysis.AnalysisJobService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SkillHub raw observation API.
 *
 * <p>Keeps the original {@code /api/observations} contract. Dashboard, node detail and
 * transcript reads are layered: old field aliases stay at the top level untouched and
 * {@code raw} / {@code analysis} / {@code analysisStatus} are added on top, so existing
 * clients never break. Read-time analysis never writes observation data back.</p>
 */
@RestController
@RequestMapping("/api/observations")
public class ObservationController {
    private final ObservationIngestService ingestService;
    private final ObservationRepository repository;
    private final ObservationAnalyzer analyzer;
    private final AnalysisJobService analysisJobs;
    private final ObservationDashboardService dashboardService;
    private final ObservationSessionAnalysisService sessionAnalysisService;
    private final ObservationReadAnalysisService readAnalysisService;
    private final ObservationSummaryRefreshService summaryRefresh;

    public ObservationController(ObservationIngestService ingestService, ObservationRepository repository,
                                 ObservationAnalyzer analyzer, AnalysisJobService analysisJobs,
                                 ObservationDashboardService dashboardService,
                                 ObservationSessionAnalysisService sessionAnalysisService,
                                 ObservationReadAnalysisService readAnalysisService,
                                 ObservationSummaryRefreshService summaryRefresh) {
        this.ingestService = ingestService;
        this.repository = repository;
        this.analyzer = analyzer;
        this.analysisJobs = analysisJobs;
        this.dashboardService = dashboardService;
        this.sessionAnalysisService = sessionAnalysisService;
        this.readAnalysisService = readAnalysisService;
        this.summaryRefresh = summaryRefresh;
    }

    /**
     * Upload contract: full session/turn/step payloads, no summaries.
     * Identity is (clientId, sessionId, turnIndex, stepId).
     * After a successful batch the read summaries refresh runs asynchronously.
     */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = ingestService.ingest(body);
        summaryRefresh.requestReadSummaries();
        return result;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("overview", repository.overview());
        result.put("skills", repository.listObservedSkills());
        return result;
    }

    @GetMapping("/sessions")
    public Map<String, Object> sessions(@RequestParam(required = false) String clientId,
                                        @RequestParam(required = false) Long sessionId) {
        return repository.sessionList(clientId, sessionId);
    }

    @GetMapping("/skills")
    public List<Map<String, Object>> skills() {
        return repository.listObservedSkills();
    }

    @GetMapping("/skills/{slug}")
    public Map<String, Object> skill(@PathVariable String slug,
                                     @RequestParam(required = false) String clientId,
                                     @RequestParam(required = false) Long sessionId,
                                     @RequestParam(required = false) String version,
                                     @RequestParam(required = false) Boolean analyze) {
        Map<String, Object> detail;
        try {
            detail = repository.skillDetail(slug, clientId, sessionId, version);
        } catch (IllegalArgumentException ex) {
            // Platform skill missing only — keep other clients on a structured 400.
            throw ex;
        } catch (RuntimeException ex) {
            // Observation data missing/broken must not 500 the metrics page.
            detail = degradedDetail(slug);
        }
        detail.put("analysis", safeAnalysis(slug, version, Boolean.TRUE.equals(analyze)));
        return detail;
    }

    /** The optimization report on its own, for pages that only need the findings. */
    @GetMapping("/skills/{slug}/analysis")
    public Map<String, Object> analysis(@PathVariable String slug,
                                        @RequestParam(required = false) String version,
                                        @RequestParam(required = false) Boolean refresh) {
        return safeAnalysis(slug, version, Boolean.TRUE.equals(refresh));
    }

    /** Async recompute: returns a job id immediately, poll the status endpoint for the report. */
    @PostMapping("/skills/{slug}/analyze")
    public Map<String, Object> analyzeAsync(@PathVariable String slug,
                                            @RequestParam(required = false) String version) {
        String jobId = analysisJobs.start(slug, version);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("jobId", jobId);
        result.put("status", "running");
        return result;
    }

    @GetMapping("/analysis/jobs/{jobId}")
    public Map<String, Object> analysisJob(@PathVariable String jobId) {
        return analysisJobs.status(jobId);
    }

    /** Step checklist that completeness is measured against. */
    @GetMapping("/skills/{slug}/contract")
    public Map<String, Object> contract(@PathVariable String slug,
                                        @RequestParam(required = false) String version,
                                        @RequestParam(required = false) Boolean refresh) {
        Map<String, Object> view = analyzer.ensureContract(slug, version, Boolean.TRUE.equals(refresh));
        if (view == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("hasContract", Boolean.FALSE);
            empty.put("message", "该技能暂时没有可用的步骤清单");
            return empty;
        }
        return view;
    }

    /** Skills that show up together and may be stepping on each other. */
    @GetMapping("/analysis/conflicts")
    public Map<String, Object> conflicts(@RequestParam(required = false) String slug) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("pairs", analyzer.allConflicts(slug));
        return result;
    }

    /** Compare the routes sessions took to get the same job done. */
    @GetMapping("/analysis/paths")
    public Map<String, Object> paths(@RequestParam String slug) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("slug", slug);
        result.put("paths", analyzer.pathsFor(slug));
        return result;
    }

    @GetMapping("/skills/{slug}/versions")
    public List<Map<String, Object>> skillVersions(@PathVariable String slug) {
        return repository.skillVersions(slug);
    }

    /**
     * Transcript: the session chain. Layered response — old aliases preserved at the
     * top level, plus raw / analysis / analysisStatus.
     */
    @GetMapping("/sessions/{id}")
    public Map<String, Object> session(@PathVariable long id,
                                       @RequestParam(required = false) String skill,
                                       @RequestParam(required = false) String version) {
        return layeredRead(repository.sessionChain(id, skill, version));
    }

    @GetMapping("/sessions/{id}/chain")
    public Map<String, Object> chain(@PathVariable long id,
                                     @RequestParam(required = false) String skill,
                                     @RequestParam(required = false) String version) {
        return layeredRead(repository.sessionChain(id, skill, version));
    }

    /** Session-scoped skill analysis: rules + coverage over this session's turns only. */
    @GetMapping("/sessions/{id}/analysis")
    public Map<String, Object> sessionAnalysis(@PathVariable long id,
                                               @RequestParam String skill,
                                               @RequestParam(required = false) String version,
                                               @RequestParam(required = false) Boolean refresh) {
        return sessionAnalysisService.analyze(id, skill, version, Boolean.TRUE.equals(refresh));
    }

    /** Single-page dashboard: graph / waterfall / stats / transcript / files assembly. */
    @GetMapping("/sessions/{id}/dashboard")
    public Map<String, Object> sessionDashboard(@PathVariable long id,
                                                @RequestParam(required = false) String skill,
                                                @RequestParam(required = false) String version) {
        return layeredRead(dashboardService.sessionDashboard(id, skill, version));
    }

    /** One node's full detail (payload body, args/result/files/usage), layered as well. */
    @GetMapping("/sessions/{id}/dashboard/node")
    public Map<String, Object> dashboardNode(@PathVariable long id,
                                             @RequestParam String nodeId) {
        return layeredRead(dashboardService.dashboardNode(id, nodeId));
    }

    /** Session full-text search over payload/session content. */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String q,
                                            @RequestParam(required = false) String skill,
                                            @RequestParam(required = false) String version,
                                            @RequestParam(required = false, defaultValue = "30") int limit) {
        return dashboardService.searchSessions(skill, version, q, limit);
    }

    /**
     * Layered read: old field aliases stay at the top level, then raw, analysis and
     * analysisStatus are added. The raw input is never mutated.
     */
    private Map<String, Object> layeredRead(Map<String, Object> raw) {
        return ObservationReadResponse.layer(raw, readAnalysisService.analyze(raw, loadPlatformCatalog()));
    }

    /**
     * Skill / published-version catalog for read analysis; a query failure marks it
     * unavailable so the analysis degrades instead of failing the read.
     */
    private Map<String, Object> loadPlatformCatalog() {
        Map<String, Object> catalog = new LinkedHashMap<String, Object>();
        try {
            Map<String, String> skills = repository.platformSkills();
            Map<String, Set<String>> published = repository.publishedVersionsBySlug();
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (Map.Entry<String, String> entry : skills.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("slug", entry.getKey());
                item.put("name", entry.getValue());
                Set<String> labels = published.get(entry.getKey());
                List<String> versions = new ArrayList<String>();
                if (labels != null) versions.addAll(labels);
                Collections.sort(versions);
                item.put("versions", versions);
                items.add(item);
            }
            catalog.put("available", Boolean.TRUE);
            catalog.put("skills", items);
        } catch (RuntimeException ex) {
            catalog.put("available", Boolean.FALSE);
            catalog.put("skills", Collections.emptyList());
            catalog.put("error", String.valueOf(ex.getMessage()));
        }
        return catalog;
    }

    /** Analysis is a side path: a failure here must never break the metrics page. */
    private Map<String, Object> safeAnalysis(String slug, String version, boolean refresh) {
        try {
            return analyzer.analyze(slug, version, refresh);
        } catch (RuntimeException ex) {
            Map<String, Object> fallback = new LinkedHashMap<String, Object>();
            fallback.put("slug", slug);
            fallback.put("degraded", Boolean.TRUE);
            fallback.put("message", "暂无法生成优化建议，观测数据可能还不完整");
            fallback.put("contract", Collections.emptyMap());
            fallback.put("summary", Collections.emptyMap());
            fallback.put("problems", Collections.emptyList());
            fallback.put("coverage", Collections.emptyMap());
            fallback.put("conflicts", Collections.emptyList());
            fallback.put("paths", Collections.emptyList());
            fallback.put("suggestions", Collections.emptyList());
            return fallback;
        }
    }

    private Map<String, Object> degradedDetail(String slug) {
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        Map<String, Object> skillCard = new LinkedHashMap<String, Object>();
        skillCard.put("slug", slug);
        skillCard.put("name", slug);
        fallback.put("skill", skillCard);
        fallback.put("versions", Collections.emptyList());
        fallback.put("clients", Collections.emptyList());
        fallback.put("sessions", Collections.emptyList());
        fallback.put("problemSessions", Collections.emptyList());
        fallback.put("trend", Collections.emptyList());
        Map<String, Object> kpis = new LinkedHashMap<String, Object>();
        kpis.put("callCount", 0);
        kpis.put("sessionCount", 0);
        kpis.put("clientCount", 0);
        kpis.put("turnCount", 0);
        kpis.put("tokenTotal", 0L);
        kpis.put("tokenInput", 0L);
        kpis.put("tokenCacheRead", 0L);
        kpis.put("tokenCacheWrite", 0L);
        kpis.put("tokenOutput", 0L);
        kpis.put("tokenRequests", 0L);
        fallback.put("kpis", kpis);
        Map<String, Object> quality = new LinkedHashMap<String, Object>();
        quality.put("calls", 0);
        quality.put("errors", 0);
        quality.put("reloadTurns", 0);
        quality.put("errorRate", 0d);
        quality.put("reloadRate", 0d);
        quality.put("checklistCoverage", -1d);
        quality.put("coverageLabel", "暂无法评估");
        fallback.put("quality", quality);
        fallback.put("selectedSessionId", null);
        fallback.put("selectedSession", null);
        fallback.put("degraded", true);
        fallback.put("message", "暂无可用观测数据，已返回空指标");
        return fallback;
    }
}
