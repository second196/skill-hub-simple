package com.km.skillhub.observation;

import com.km.skillhub.analysis.AnalysisJobService;
import com.km.skillhub.analysis.ObservationAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller wiring: legacy endpoints stay layered (raw/analysis/analysisStatus plus
 * old aliases), the four new endpoints delegate, and a successful ingest kicks the
 * summary refresh while a failed one does not.
 */
class ObservationControllerLayeredTest {

    private ObservationIngestService ingestService;
    private ObservationRepository repository;
    private ObservationDashboardService dashboardService;
    private ObservationSessionAnalysisService sessionAnalysisService;
    private ObservationReadAnalysisService readAnalysisService;
    private ObservationSummaryRefreshService summaryRefresh;
    private ObservationController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ingestService = mock(ObservationIngestService.class);
        repository = mock(ObservationRepository.class);
        dashboardService = mock(ObservationDashboardService.class);
        sessionAnalysisService = mock(ObservationSessionAnalysisService.class);
        readAnalysisService = mock(ObservationReadAnalysisService.class);
        summaryRefresh = mock(ObservationSummaryRefreshService.class);
        controller = new ObservationController(ingestService, repository,
                mock(ObservationAnalyzer.class), mock(AnalysisJobService.class),
                dashboardService, sessionAnalysisService, readAnalysisService, summaryRefresh);
    }

    private void stubReadAnalysis() {
        when(readAnalysisService.analyze(anyMap(), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> raw = invocation.getArgument(0);
            Map<String, Object> analysis = new LinkedHashMap<String, Object>();
            analysis.put("analysisSource", "runtimeComputed");
            return new ObservationReadResponse(raw, analysis, ObservationReadResponse.STATUS_COMPUTED);
        });
    }

    @Test
    void sessionAndChainStayLayeredWithOldAliases() {
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("id", Long.valueOf(7L));
        raw.put("session_key", "sess-7");
        raw.put("turns", Collections.emptyList());
        when(repository.sessionChain(7L, null, null)).thenReturn(raw);
        stubReadAnalysis();

        Map<String, Object> session = controller.session(7L, null, null);
        assertEquals(Long.valueOf(7L), session.get("id"));
        assertEquals("sess-7", session.get("session_key"));
        assertTrue(session.containsKey("turns"));
        assertEquals(raw, session.get("raw"));
        assertEquals(ObservationReadResponse.STATUS_COMPUTED, session.get("analysisStatus"));
        assertTrue(session.get("analysis") instanceof Map);

        Map<String, Object> chain = controller.chain(7L, null, null);
        assertEquals(ObservationReadResponse.STATUS_COMPUTED, chain.get("analysisStatus"));
        assertEquals(raw, chain.get("raw"));
    }

    @Test
    void dashboardAndNodeReadsAreLayered() {
        Map<String, Object> dash = new LinkedHashMap<String, Object>();
        dash.put("nodes", Collections.emptyList());
        dash.put("stats", Collections.emptyMap());
        when(dashboardService.sessionDashboard(7L, "demo", "1.0.0")).thenReturn(dash);
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("node", Collections.emptyMap());
        when(dashboardService.dashboardNode(7L, "nid-1")).thenReturn(node);
        stubReadAnalysis();

        Map<String, Object> dashboard = controller.sessionDashboard(7L, "demo", "1.0.0");
        assertTrue(dashboard.containsKey("nodes"));
        assertTrue(dashboard.containsKey("stats"));
        assertEquals(dash, dashboard.get("raw"));
        assertEquals(ObservationReadResponse.STATUS_COMPUTED, dashboard.get("analysisStatus"));

        Map<String, Object> nodeDetail = controller.dashboardNode(7L, "nid-1");
        assertTrue(nodeDetail.containsKey("node"));
        assertEquals(node, nodeDetail.get("raw"));
        assertEquals(ObservationReadResponse.STATUS_COMPUTED, nodeDetail.get("analysisStatus"));
    }

    @Test
    void searchDelegatesToDashboardService() {
        List<Map<String, Object>> hits = Collections.singletonList(
                Collections.<String, Object>singletonMap("session_id", Long.valueOf(1L)));
        when(dashboardService.searchSessions("demo", "1.0.0", "keyword", 30)).thenReturn(hits);

        assertEquals(hits, controller.search("keyword", "demo", "1.0.0", 30));
        verify(dashboardService).searchSessions("demo", "1.0.0", "keyword", 30);
    }

    @Test
    void sessionAnalysisDelegatesWithRefreshFlag() {
        Map<String, Object> report = Collections.<String, Object>singletonMap("slug", "demo");
        when(sessionAnalysisService.analyze(7L, "demo", "1.0.0", true)).thenReturn(report);

        assertEquals(report, controller.sessionAnalysis(7L, "demo", "1.0.0", Boolean.TRUE));
        verify(sessionAnalysisService).analyze(7L, "demo", "1.0.0", true);
        verify(sessionAnalysisService, never()).analyze(7L, "demo", "1.0.0", false);
    }

    @Test
    void successfulIngestRequestsSummaryRefresh() {
        Map<String, Object> body = Collections.singletonMap("client",
                Collections.singletonMap("clientId", "c1"));
        Map<String, Object> result = Collections.<String, Object>singletonMap("stepCount", 3);
        when(ingestService.ingest(body)).thenReturn(result);

        assertEquals(result, controller.ingest(body));
        verify(summaryRefresh).requestReadSummaries();
    }

    @Test
    void failedIngestDoesNotRequestSummaryRefresh() {
        Map<String, Object> body = Collections.singletonMap("client",
                Collections.singletonMap("clientId", "c1"));
        when(ingestService.ingest(body)).thenThrow(new IllegalArgumentException("上传内容不能为空"));

        try {
            controller.ingest(body);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected: error path must not mark summaries dirty
        }
        verify(summaryRefresh, never()).requestReadSummaries();
    }

    @Test
    void platformCatalogFallsBackToUnavailableOnFailure() {
        when(repository.platformSkills()).thenThrow(new IllegalStateException("db down"));
        when(readAnalysisService.analyze(anyMap(), anyMap())).thenReturn(
                new ObservationReadResponse());
        when(repository.sessionChain(1L, null, null)).thenReturn(new LinkedHashMap<String, Object>());

        controller.session(1L, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
        verify(readAnalysisService).analyze(anyMap(), captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().get("available"));
        assertTrue(((List<?>) captor.getValue().get("skills")).isEmpty());
        assertTrue(captor.getValue().containsKey("error"));
    }

    @Test
    void platformCatalogExposesSkillsWithVersions() {
        Map<String, String> skills = new LinkedHashMap<String, String>();
        skills.put("demo", "Demo Skill");
        when(repository.platformSkills()).thenReturn(skills);
        when(repository.publishedVersionsBySlug()).thenReturn(
                Collections.singletonMap("demo",
                        new java.util.HashSet<String>(Collections.singletonList("1.0.0"))));
        when(readAnalysisService.analyze(anyMap(), anyMap())).thenReturn(
                new ObservationReadResponse());
        when(repository.sessionChain(eq(2L), any(), any())).thenReturn(new LinkedHashMap<String, Object>());

        controller.session(2L, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
        verify(readAnalysisService).analyze(anyMap(), captor.capture());
        Map<String, Object> catalog = captor.getValue();
        assertEquals(Boolean.TRUE, catalog.get("available"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) catalog.get("skills");
        assertEquals(1, items.size());
        assertEquals("demo", items.get(0).get("slug"));
        assertEquals(Collections.singletonList("1.0.0"), items.get(0).get("versions"));
    }
}
