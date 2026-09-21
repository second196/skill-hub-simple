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
        return repository.skillDetail(slug, clientId, sessionId, version);
    }

    /**
     * Observed + published version list for a skill dropdown.
     * Combines skill_version (formal) with DISTINCT observation_step.skill_version_digest.
     */
    @GetMapping("/skills/{slug}/versions")
    public List<Map<String, Object>> skillVersions(@PathVariable String slug) {
        return repository.skillVersions(slug);
    }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> session(@PathVariable long id, @RequestParam(required = false) String skill) {
        return repository.sessionChain(id, skill);
    }

    @GetMapping("/sessions/{id}/chain")
    public Map<String, Object> chain(@PathVariable long id, @RequestParam(required = false) String skill) {
        return repository.sessionChain(id, skill);
    }
}
