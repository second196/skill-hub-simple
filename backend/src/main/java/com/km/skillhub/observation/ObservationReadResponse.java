package com.km.skillhub.observation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Layered observation read response: top-level {@code raw} / {@code analysis} /
 * {@code analysisStatus}.
 *
 * <p>{@code raw} is the original query result, {@code analysis} is computed at read
 * time; neither overwrites the other. Old field aliases live at the top level of the
 * merged map (see {@link #layer}), so pre-existing clients keep working.</p>
 */
public class ObservationReadResponse {

    public static final String STATUS_COMPUTED = "computed";
    public static final String STATUS_LEGACY_STORED = "legacyStored";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_UNAVAILABLE = "unavailable";

    public static final String SOURCE_RUNTIME_COMPUTED = "runtimeComputed";
    public static final String SOURCE_LEGACY_STORED = "legacyStored";
    public static final String SOURCE_PARTIAL = "partial";
    public static final String SOURCE_UNAVAILABLE = "unavailable";

    private Map<String, Object> raw;
    private Map<String, Object> analysis;
    private String analysisStatus;

    public ObservationReadResponse() {
        this.raw = Collections.emptyMap();
        this.analysis = Collections.emptyMap();
        this.analysisStatus = STATUS_UNAVAILABLE;
    }

    public ObservationReadResponse(Map<String, Object> raw,
                                   Map<String, Object> analysis,
                                   String analysisStatus) {
        this.raw = raw == null ? Collections.<String, Object>emptyMap() : raw;
        this.analysis = analysis == null ? Collections.<String, Object>emptyMap() : analysis;
        this.analysisStatus = analysisStatus == null ? STATUS_UNAVAILABLE : analysisStatus;
    }

    public Map<String, Object> getRaw() {
        return raw;
    }

    public void setRaw(Map<String, Object> raw) {
        this.raw = raw == null ? Collections.<String, Object>emptyMap() : raw;
    }

    public Map<String, Object> getAnalysis() {
        return analysis;
    }

    public void setAnalysis(Map<String, Object> analysis) {
        this.analysis = analysis == null ? Collections.<String, Object>emptyMap() : analysis;
    }

    public String getAnalysisStatus() {
        return analysisStatus;
    }

    public void setAnalysisStatus(String analysisStatus) {
        this.analysisStatus = analysisStatus == null ? STATUS_UNAVAILABLE : analysisStatus;
    }

    /** Only the three layered fields, no aliases. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("raw", raw);
        map.put("analysis", analysis);
        map.put("analysisStatus", analysisStatus);
        return map;
    }

    /**
     * Merge into the response the controller returns: old top-level fields are kept
     * untouched as aliases, then {@code raw}/{@code analysis}/{@code analysisStatus}
     * are added. Never mutates {@code original}.
     */
    public static Map<String, Object> layer(Map<String, Object> original, ObservationReadResponse response) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (original != null) {
            result.putAll(original);
        }
        ObservationReadResponse safe = response == null ? new ObservationReadResponse() : response;
        result.put("raw", safe.getRaw());
        result.put("analysis", safe.getAnalysis());
        result.put("analysisStatus", safe.getAnalysisStatus());
        return result;
    }
}
