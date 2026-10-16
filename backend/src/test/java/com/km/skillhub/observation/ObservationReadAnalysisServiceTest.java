package com.km.skillhub.observation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layered raw/analysis/analysisStatus wrapping — the compatibility contract that
 * keeps old field aliases on top of every transcript/dashboard read.
 */
class ObservationReadAnalysisServiceTest {

    private final ObservationReadAnalysisService service = new ObservationReadAnalysisServiceImpl();

    private static Map<String, Object> catalog(String slug) {
        Map<String, Object> skill = new LinkedHashMap<String, Object>();
        skill.put("slug", slug);
        skill.put("name", slug);
        skill.put("versions", Collections.singletonList("1.0.0"));
        Map<String, Object> catalog = new LinkedHashMap<String, Object>();
        catalog.put("available", Boolean.TRUE);
        catalog.put("skills", Collections.singletonList(skill));
        return catalog;
    }

    private static Map<String, Object> rawSession(long id) {
        Map<String, Object> toolPayload = new LinkedHashMap<String, Object>();
        toolPayload.put("name", "read_file");
        toolPayload.put("args", Collections.singletonMap("path", "a.md"));
        Map<String, Object> toolStep = new LinkedHashMap<String, Object>();
        toolStep.put("type", "tool");
        toolStep.put("stepId", "t-1");
        toolStep.put("seq", 1);
        toolStep.put("payload", toolPayload);
        // A skill step keeps the session non-legacy (column/payload carry no stored skill fields)
        // while still giving the read analysis an association to resolve against the catalog.
        Map<String, Object> skillPayload = new LinkedHashMap<String, Object>();
        skillPayload.put("name", "demo-skill");
        Map<String, Object> skillStep = new LinkedHashMap<String, Object>();
        skillStep.put("type", "skill");
        skillStep.put("stepId", "s-1");
        skillStep.put("seq", 2);
        skillStep.put("payload", skillPayload);
        Map<String, Object> turn = new LinkedHashMap<String, Object>();
        turn.put("steps", java.util.Arrays.asList(toolStep, skillStep));
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("id", Long.valueOf(id));
        raw.put("turns", Collections.singletonList(turn));
        return raw;
    }

    @Test
    void layerKeepsOldAliasesAndAddsThreeTopLevelFields() {
        Map<String, Object> original = new LinkedHashMap<String, Object>();
        original.put("id", Long.valueOf(7L));
        original.put("session_key", "abc");
        original.put("turns", new ArrayList<Object>());

        ObservationReadResponse response = new ObservationReadResponse(original,
                Collections.<String, Object>singletonMap("stats", Collections.emptyMap()),
                ObservationReadResponse.STATUS_COMPUTED);
        Map<String, Object> merged = ObservationReadResponse.layer(original, response);

        // old aliases preserved
        assertEquals(Long.valueOf(7L), merged.get("id"));
        assertEquals("abc", merged.get("session_key"));
        assertTrue(merged.containsKey("turns"));
        // layered fields present
        assertSame(original, merged.get("raw"));
        assertTrue(merged.get("analysis") instanceof Map);
        assertEquals(ObservationReadResponse.STATUS_COMPUTED, merged.get("analysisStatus"));
        // original untouched (no layered keys written back)
        assertFalse(original.containsKey("raw"));
        assertFalse(original.containsKey("analysis"));
        assertFalse(original.containsKey("analysisStatus"));
    }

    @Test
    void layerHandlesNullResponseAndNullOriginal() {
        Map<String, Object> merged = ObservationReadResponse.layer(null, null);
        assertEquals(ObservationReadResponse.STATUS_UNAVAILABLE, merged.get("analysisStatus"));
        assertTrue(((Map<?, ?>) merged.get("raw")).isEmpty());
        assertTrue(((Map<?, ?>) merged.get("analysis")).isEmpty());
    }

    @Test
    void analyzeReportsComputedWithViewModelsAndDoesNotMutateRaw() {
        Map<String, Object> raw = rawSession(11L);
        ObservationReadResponse response = service.analyze(raw, catalog("demo-skill"));

        assertEquals(ObservationReadResponse.STATUS_COMPUTED, response.getAnalysisStatus());
        assertSame(raw, response.getRaw());
        assertFalse(raw.containsKey("analysis"));
        Map<String, Object> analysis = response.getAnalysis();
        assertTrue(analysis.get("graph") instanceof Map);
        assertTrue(analysis.get("waterfall") instanceof List);
        assertTrue(analysis.get("stats") instanceof Map);
        assertTrue(analysis.get("files") instanceof List);
        assertTrue(analysis.containsKey("tokenAttribution"));
        assertTrue(analysis.containsKey("quality"));
        assertEquals(ObservationReadResponse.SOURCE_RUNTIME_COMPUTED, analysis.get("analysisSource"));
        // catalog marks the tool's inferred skill as in-catalog
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> associations = (List<Map<String, Object>>) analysis.get("skillAssociations");
        assertFalse(associations.isEmpty());
        assertEquals(Boolean.TRUE, associations.get(0).get("inCatalog"));
    }

    @Test
    void analyzeMarksLegacyStoredPayloads() {
        Map<String, Object> raw = rawSession(12L);
        @SuppressWarnings("unchecked")
        Map<String, Object> turn = ((List<Map<String, Object>>) raw.get("turns")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstStep = ((List<Map<String, Object>>) turn.get("steps")).get(0);
        Map<String, Object> payload = (Map<String, Object>) firstStep.get("payload");
        payload.put("skill_slug", "legacy-skill");

        ObservationReadResponse response = service.analyze(raw, catalog("demo-skill"));
        assertEquals(ObservationReadResponse.STATUS_LEGACY_STORED, response.getAnalysisStatus());
        assertEquals(ObservationReadResponse.SOURCE_LEGACY_STORED,
                response.getAnalysis().get("analysisSource"));
    }

    @Test
    void analyzeDegradesWhenCatalogUnavailable() {
        ObservationReadResponse response = service.analyze(rawSession(13L), null);
        assertEquals(ObservationReadResponse.STATUS_UNAVAILABLE, response.getAnalysisStatus());
        assertEquals(ObservationReadResponse.SOURCE_UNAVAILABLE,
                response.getAnalysis().get("analysisSource"));
        // raw still returned so the page can render
        assertEquals(Long.valueOf(13L), response.getRaw().get("id"));
    }

    @Test
    void hostileRawMapDegradesToUnavailableInsteadOfThrowing() {
        Map<String, Object> raw = new LinkedHashMap<String, Object>() {
            @Override
            public Object get(Object key) {
                throw new IllegalStateException("boom");
            }
        };
        ObservationReadResponse response = service.analyze(raw, catalog("demo-skill"));
        assertEquals(ObservationReadResponse.STATUS_UNAVAILABLE, response.getAnalysisStatus());
        assertTrue(response.getAnalysis().containsKey("skillAssociations"));
        assertTrue(response.getAnalysis().containsKey("stats"));
    }

    @Test
    void cacheKeySeparatesSessions() {
        Map<String, Object> a = rawSession(21L);
        Map<String, Object> b = rawSession(22L);
        assertFalse(ObservationReadAnalysisServiceImpl.buildCacheKey(a, catalog("s"))
                .equals(ObservationReadAnalysisServiceImpl.buildCacheKey(b, catalog("s"))));
    }
}
