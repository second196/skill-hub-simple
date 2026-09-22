package com.km.skillhub.observation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/observations")
public class ObservationController {
    private final ObservationIngestService ingestService;
    private final ObservationRepository repository;

    public ObservationController(ObservationIngestService ingestService, ObservationRepository repository) {
        this.ingestService = ingestService;
        this.repository = repository;
    }

    /**
     * Upload contract: full session/turn/step payloads, no summaries.
     * Identity is (clientId, sessionId, turnIndex, stepId).
     */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody Map<String, Object> body) {
        return ingestService.ingest(body);
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
                                     @RequestParam(required = false) String version) {
        try {
            return repository.skillDetail(slug, clientId, sessionId, version);
        } catch (IllegalArgumentException ex) {
            // Platform skill missing only — keep other clients on a structured 400.
            throw ex;
        } catch (RuntimeException ex) {
            // Observation data missing/broken must not 500 the metrics page.
            Map<String, Object> fallback = new LinkedHashMap<String, Object>();
            Map<String, Object> skillCard = new LinkedHashMap<String, Object>();
            skillCard.put("slug", slug);
            skillCard.put("name", slug);
            fallback.put("skill", skillCard);
            fallback.put("versions", java.util.Collections.emptyList());
            fallback.put("clients", java.util.Collections.emptyList());
            fallback.put("sessions", java.util.Collections.emptyList());
            fallback.put("problemSessions", java.util.Collections.emptyList());
            fallback.put("trend", java.util.Collections.emptyList());
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
            quality.put("completeLoads", 0);
            quality.put("healthScore", 0);
            quality.put("errorRate", 0d);
            quality.put("reloadRate", 0d);
            quality.put("loadCompleteRate", 0d);
            fallback.put("quality", quality);
            fallback.put("selectedSessionId", null);
            fallback.put("selectedSession", null);
            fallback.put("degraded", true);
            fallback.put("message", "暂无可用观测数据，已返回空指标");
            return fallback;
        }
    }

    /**
     * Observed + published version labels for a skill dropdown.
     * Observation identity is SemVer skill_version_label only (no content digest).
     */
    @GetMapping("/skills/{slug}/versions")
    public List<Map<String, Object>> skillVersions(@PathVariable String slug) {
        return repository.skillVersions(slug);
    }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> session(@PathVariable long id,
                                       @RequestParam(required = false) String skill,
                                       @RequestParam(required = false) String version) {
        return repository.sessionChain(id, skill, version);
    }

    @GetMapping("/sessions/{id}/chain")
    public Map<String, Object> chain(@PathVariable long id,
                                     @RequestParam(required = false) String skill,
                                     @RequestParam(required = false) String version) {
        return repository.sessionChain(id, skill, version);
    }
}
