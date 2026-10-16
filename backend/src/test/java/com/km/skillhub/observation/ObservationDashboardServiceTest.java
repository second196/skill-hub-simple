package com.km.skillhub.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dashboard assembly (graph/waterfall/stats/transcript/files), node detail and
 * search delegation/clamping.
 */
class ObservationDashboardServiceTest {

    private static final class StubRepository extends ObservationDashboardRepository {
        private final List<Map<String, Object>> sessions;
        private final List<Map<String, Object>> turns;
        private final List<Map<String, Object>> steps;
        Map<String, Object> nodeRow;
        List<Map<String, Object>> searchHits = new ArrayList<Map<String, Object>>();
        String searchSlug;
        String searchVersion;
        String searchQuery;
        int searchLimit;

        StubRepository(List<Map<String, Object>> sessions,
                       List<Map<String, Object>> turns,
                       List<Map<String, Object>> steps) {
            super(null);
            this.sessions = sessions;
            this.turns = turns;
            this.steps = steps;
        }

        @Override
        public List<Map<String, Object>> dashboardSessions(long sessionId) {
            if (sessions.isEmpty()) return sessions;
            return sessions;
        }

        @Override
        public List<Map<String, Object>> dashboardTurns(List<Long> sessionIds, String slug,
                                                        String versionFilter, int limit) {
            return turns.size() > limit ? new ArrayList<Map<String, Object>>(turns.subList(0, limit)) : turns;
        }

        @Override
        public List<Map<String, Object>> dashboardSteps(List<Long> turnIds, int limit) {
            return steps;
        }

        @Override
        public Map<String, Object> dashboardNode(long sessionId, int turnIndex, String stepId) {
            return nodeRow;
        }

        @Override
        public List<Map<String, Object>> searchHits(String slug, String versionFilter,
                                                    String query, int limit) {
            this.searchSlug = slug;
            this.searchVersion = versionFilter;
            this.searchQuery = query;
            this.searchLimit = limit;
            return searchHits;
        }
    }

    private static Map<String, Object> session(long id, String key) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Long.valueOf(id));
        row.put("session_key", key);
        row.put("client_name", "codex");
        row.put("client_id", "c1");
        row.put("hostname", "host");
        row.put("started_at", "2026-01-01T00:00:00Z");
        row.put("depth", Integer.valueOf(0));
        return row;
    }

    private static Map<String, Object> turn(long id, long sessionId, int index) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Long.valueOf(id));
        row.put("session_id", Long.valueOf(sessionId));
        row.put("turn_index", Integer.valueOf(index));
        row.put("user_text", "帮我读取 a.md 并总结");
        return row;
    }

    private static Map<String, Object> step(long turnId, String stepId, int seq, String type,
                                            Map<String, Object> payload) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("turn_id", Long.valueOf(turnId));
        row.put("step_id", stepId);
        row.put("seq", Integer.valueOf(seq));
        row.put("type", type);
        row.put("ts", "2026-01-01T00:00:01Z");
        row.put("payload", payload);
        return row;
    }

    private static Map<String, Object> toolPayload(String name) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        payload.put("args", Collections.singletonMap("path", "docs/a.md"));
        payload.put("file_ops", Collections.singletonList(
                Collections.singletonMap("path", "docs/a.md")));
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        usage.put("total_tokens", Integer.valueOf(10));
        // usageSource only counts direct usage: scope lives inside the usage object
        usage.put("scope", "message");
        payload.put("usage", usage);
        payload.put("duration_ms", Integer.valueOf(120));
        return payload;
    }

    private ObservationDashboardService service(StubRepository repository) {
        return new ObservationDashboardServiceImpl(repository, new ObjectMapper());
    }

    @Test
    void dashboardAssemblesAllFiveViewModels() {
        Map<String, Object> payload = toolPayload("read_file");
        StubRepository repository = new StubRepository(
                Collections.singletonList(session(1L, "sess-1")),
                Collections.singletonList(turn(10L, 1L, 0)),
                Collections.singletonList(step(10L, "s1", 1, "tool", payload)));
        ObservationDashboardService service = service(repository);

        Map<String, Object> dashboard = service.sessionDashboard(1L, null, null);

        assertTrue(dashboard.get("session") instanceof Map);
        assertTrue(dashboard.get("nodes") instanceof List);
        assertTrue(dashboard.get("edges") instanceof List);
        assertTrue(dashboard.get("turns") instanceof List);
        assertTrue(dashboard.get("files") instanceof List);
        assertTrue(dashboard.get("stats") instanceof Map);
        assertTrue(dashboard.get("subagents") instanceof List);
        assertTrue(dashboard.get("truncated") instanceof Map);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) dashboard.get("nodes");
        assertEquals(1, nodes.size());
        // node ids must round-trip through the codec
        String nodeId = String.valueOf(nodes.get(0).get("nodeId"));
        ObservationNodeIdCodec.Decoded decoded = ObservationNodeIdCodec.decode(nodeId);
        assertEquals("step", decoded.getKind());
        assertEquals("s1", decoded.getStepId());
        assertEquals(Integer.valueOf(0), decoded.getTurnIndex());

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) dashboard.get("stats");
        assertEquals(Integer.valueOf(1), stats.get("toolCallCount"));
        assertEquals(Long.valueOf(10L), stats.get("totalTokens"));
        assertEquals(Integer.valueOf(1), stats.get("changedFileCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> turns = (List<Map<String, Object>>) dashboard.get("turns");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> turnSteps = (List<Map<String, Object>>) turns.get(0).get("steps");
        assertEquals("s1", turnSteps.get(0).get("stepId"));
        assertNotNull(turnSteps.get(0).get("payload"));
    }

    @Test
    void dashboardRejectsUnknownSession() {
        StubRepository repository = new StubRepository(
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList());
        ObservationDashboardService service = service(repository);
        assertThrows(IllegalArgumentException.class, () -> service.sessionDashboard(99L, null, null));
    }

    @Test
    void nodeDetailReturnsContentForStepNode() {
        StubRepository repository = new StubRepository(
                Collections.singletonList(session(1L, "sess-1")),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList());
        Map<String, Object> row = step(10L, "s1", 1, "tool", toolPayload("read_file"));
        row.put("session_id", Long.valueOf(1L));
        row.put("turn_index", Integer.valueOf(0));
        repository.nodeRow = row;
        ObservationDashboardService service = service(repository);

        String nodeId = ObservationNodeIdCodec.encodeStepNode(1L, 0, "s1");
        Map<String, Object> detail = service.dashboardNode(1L, nodeId);

        assertTrue(detail.get("node") instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) detail.get("content");
        assertTrue(content.containsKey("args"));
        assertTrue(content.containsKey("files"));
        assertTrue(content.containsKey("usage"));
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) detail.get("node");
        assertEquals(Boolean.TRUE, node.get("detailAvailable"));
    }

    @Test
    void subagentNodeHasNoDetail() {
        StubRepository repository = new StubRepository(
                Collections.singletonList(session(1L, "sess-1")),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList());
        ObservationDashboardService service = service(repository);
        Map<String, Object> detail = service.dashboardNode(1L,
                ObservationNodeIdCodec.encodeSubagentNode(5L));
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) detail.get("node");
        assertEquals("subagent", node.get("kind"));
        assertEquals(Boolean.FALSE, node.get("detailAvailable"));
        assertTrue(((Map<?, ?>) detail.get("content")).isEmpty());
    }

    @Test
    void invalidNodeIdIsRejected() {
        StubRepository repository = new StubRepository(
                Collections.singletonList(session(1L, "sess-1")),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList());
        ObservationDashboardService service = service(repository);
        assertThrows(IllegalArgumentException.class, () -> service.dashboardNode(1L, "not-a-node"));
    }

    @Test
    void searchDelegatesAndClampsLimit() {
        StubRepository repository = new StubRepository(
                Collections.singletonList(session(1L, "sess-1")),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList());
        repository.searchHits = Collections.singletonList(
                Collections.<String, Object>singletonMap("session_id", Long.valueOf(1L)));
        ObservationDashboardService service = service(repository);

        List<Map<String, Object>> hits = service.searchSessions("demo", "1.0.0", "  keyword ", 500);
        assertEquals(1, hits.size());
        assertEquals("demo", repository.searchSlug);
        assertEquals("1.0.0", repository.searchVersion);
        assertEquals("keyword", repository.searchQuery);
        assertEquals(100, repository.searchLimit);

        assertTrue(service.searchSessions("demo", null, "   ", 10).isEmpty());
        assertFalse(service.searchSessions("demo", null, "x", -5).isEmpty());
        assertEquals(1, repository.searchLimit);
    }
}
