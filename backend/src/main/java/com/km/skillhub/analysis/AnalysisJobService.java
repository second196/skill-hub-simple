package com.km.skillhub.analysis;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small in-memory job registry for async analysis. Jobs are not persisted across
 * restarts; the API is for triggering a recompute without blocking the metrics page.
 */
@Service
public class AnalysisJobService {

    private final ObservationAnalyzer analyzer;
    private final Map<String, CompletableFuture<Map<String, Object>>> jobs =
            new ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>>();
    private final AtomicLong sequence = new AtomicLong();

    public AnalysisJobService(ObservationAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    public String start(String slug, String versionLabel) {
        String jobId = "analysis-" + System.currentTimeMillis() + "-" + sequence.incrementAndGet();
        jobs.put(jobId, CompletableFuture.supplyAsync(() -> analyzer.analyze(slug, versionLabel, true)));
        return jobId;
    }

    public Map<String, Object> status(String jobId) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("jobId", jobId);
        CompletableFuture<Map<String, Object>> future = jobs.get(jobId);
        if (future == null) {
            view.put("status", "not_found");
            return view;
        }
        if (!future.isDone()) {
            view.put("status", "running");
            return view;
        }
        try {
            Map<String, Object> result = future.getNow(null);
            view.put("status", "done");
            if (result != null) view.put("result", result);
        } catch (RuntimeException ex) {
            view.put("status", "failed");
            view.put("error", ex instanceof CompletionException && ex.getCause() != null
                    ? String.valueOf(ex.getCause().getMessage())
                    : String.valueOf(ex.getMessage()));
        }
        return view;
    }
}
