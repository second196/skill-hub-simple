package com.km.skillhub.observation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dashboard aggregation for one observation session (plus its subagent sessions).
 *
 * <p>Adapted from kmplm SkillHubObservationDashboardServiceImpl: bounded reads
 * (turns/nodes capped), graph edges derived from parent_session_key and step types —
 * no separate dashboard node table is stored, per V15 (kmplm V28 has none either).</p>
 */
@Service
public class ObservationDashboardServiceImpl implements ObservationDashboardService {

    private static final int MAX_TURNS = 200;
    private static final int MAX_NODES = 3000;
    private static final long CACHE_TTL_MS = 8_000L;

    private final ObservationDashboardRepository repository;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, CacheEntry> dashCache =
            new ConcurrentHashMap<String, CacheEntry>();

    public ObservationDashboardServiceImpl(ObservationDashboardRepository repository,
                                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> sessionDashboard(long sessionId, String skillSlug, String versionLabel) {
        String cacheKey = "dashboard:" + sessionId + ':' + skillSlug + ':' + versionLabel;
        long now = System.currentTimeMillis();
        CacheEntry cached = dashCache.get(cacheKey);
        if (cached != null && cached.expiresAt > now) {
            return cached.value;
        }
        Map<String, Object> result = buildSessionDashboard(sessionId, skillSlug, versionLabel);
        if (dashCache.size() > 64) dashCache.clear();
        dashCache.put(cacheKey, new CacheEntry(result, now + CACHE_TTL_MS));
        return result;
    }

    private Map<String, Object> buildSessionDashboard(long sessionId, String skillSlug, String versionLabel) {
        List<Map<String, Object>> sessions = repository.dashboardSessions(sessionId);
        if (sessions.isEmpty()) throw new IllegalArgumentException("观测会话不存在");
        enrichSessionTitles(sessions);

        List<Long> sessionIds = new ArrayList<Long>();
        for (Map<String, Object> session : sessions) {
            Long id = longObject(session.get("id"));
            if (id != null) sessionIds.add(id);
        }
        if (sessionIds.isEmpty()) throw new IllegalArgumentException("观测会话不存在");

        String normalizedSlug = skillSlug == null || skillSlug.trim().isEmpty() ? null : skillSlug.trim();
        List<Map<String, Object>> turns = repository.dashboardTurns(
                sessionIds, normalizedSlug, versionLabel, MAX_TURNS);
        List<Long> turnIds = new ArrayList<Long>();
        for (Map<String, Object> turn : turns) {
            Long id = longObject(turn.get("id"));
            if (id != null) turnIds.add(id);
        }
        List<Map<String, Object>> steps = turnIds.isEmpty()
                ? Collections.<Map<String, Object>>emptyList()
                : repository.dashboardSteps(turnIds, MAX_NODES + 1);

        List<ObserveNode> nodes = buildNodes(sessions, turns, steps, sessionId);
        if (nodes.size() > MAX_NODES) nodes = new ArrayList<ObserveNode>(nodes.subList(0, MAX_NODES));
        List<Map<String, Object>> nodeViews = new ArrayList<Map<String, Object>>();
        for (ObserveNode node : nodes) nodeViews.add(node.toView());

        Map<String, Object> root = sessions.get(0);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("session", sessionView(root, sessions.size() - 1));
        result.put("subagents", subagentViews(sessions));
        result.put("nodes", nodeViews);
        result.put("edges", buildEdges(nodes));
        result.put("turns", turnViews(turns, steps));
        result.put("files", aggregateFiles(nodes));
        result.put("stats", aggregateStats(nodes, turns, sessions));
        Map<String, Object> truncated = new LinkedHashMap<String, Object>();
        truncated.put("turns", Boolean.valueOf(turns.size() >= MAX_TURNS));
        truncated.put("nodes", Boolean.valueOf(nodes.size() >= MAX_NODES));
        truncated.put("reason", turns.size() >= MAX_TURNS || nodes.size() >= MAX_NODES
                ? "为保证性能，仪表盘已截断部分回合或节点" : null);
        result.put("truncated", truncated);
        return result;
    }

    @Override
    public Map<String, Object> dashboardNode(long sessionId, String nodeId) {
        ObservationNodeIdCodec.Decoded decoded = ObservationNodeIdCodec.decode(nodeId);
        if ("subagent".equals(decoded.getKind())) {
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("nodeId", nodeId);
            node.put("kind", "subagent");
            node.put("sessionRowId", Long.valueOf(decoded.getSessionRowId()));
            node.put("title", "子 agent");
            node.put("detailAvailable", Boolean.FALSE);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("node", node);
            result.put("content", Collections.emptyMap());
            return result;
        }
        Map<String, Object> row = repository.dashboardNode(
                sessionId,
                decoded.getTurnIndex() == null ? 0 : decoded.getTurnIndex().intValue(),
                decoded.getStepId());
        if (row == null) throw new IllegalArgumentException("观测节点不存在");
        ObserveNode node = ObserveNode.fromRow(row, this);
        Map<String, Object> payload = asMap(parseJson(row.get("payload")));
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("text", firstText(payload));
        content.put("args", payload.get("args"));
        content.put("result", payload.get("result"));
        content.put("files", fileOps(payload));
        content.put("imagePaths", payload.get("image_paths") instanceof List
                ? payload.get("image_paths") : Collections.emptyList());
        content.put("usage", payload.get("usage"));
        content.put("redacted", Boolean.TRUE.equals(payload.get("redacted")));
        content.put("truncated", Boolean.FALSE);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("node", node.toView());
        result.put("content", content);
        return result;
    }

    @Override
    public List<Map<String, Object>> searchSessions(String slug, String versionLabel, String query, int limit) {
        if (query == null || query.trim().isEmpty()) return Collections.emptyList();
        return repository.searchHits(slug, versionLabel, query.trim(), Math.max(1, Math.min(limit, 100)));
    }

    // ------------------------------------------------------------ graph assembly

    private List<ObserveNode> buildNodes(List<Map<String, Object>> sessions,
                                         List<Map<String, Object>> turns,
                                         List<Map<String, Object>> steps,
                                         long rootSessionId) {
        Map<Long, Map<String, Object>> sessionById = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> session : sessions) sessionById.put(longObject(session.get("id")), session);
        Map<Long, List<Map<String, Object>>> stepsByTurn = new LinkedHashMap<Long, List<Map<String, Object>>>();
        for (Map<String, Object> step : steps) {
            Long turnId = longObject(step.get("turn_id"));
            List<Map<String, Object>> list = stepsByTurn.get(turnId);
            if (list == null) {
                list = new ArrayList<Map<String, Object>>();
                stepsByTurn.put(turnId, list);
            }
            list.add(step);
        }
        List<ObserveNode> nodes = new ArrayList<ObserveNode>();
        for (Map<String, Object> turn : turns) {
            Long turnId = longObject(turn.get("id"));
            Long sessionRowId = longObject(turn.get("session_id"));
            Map<String, Object> session = sessionById.get(sessionRowId);
            List<Map<String, Object>> turnSteps = stepsByTurn.get(turnId);
            if (turnSteps == null) turnSteps = Collections.emptyList();
            for (Map<String, Object> step : turnSteps) {
                nodes.add(ObserveNode.fromStep(sessionRowId, session, turn, step, rootSessionId, this));
            }
            if (turnSteps.isEmpty() && !stringOf(turn.get("user_text")).isEmpty()) {
                nodes.add(ObserveNode.fromUserTurn(sessionRowId, session, turn, rootSessionId));
            }
        }
        for (Map<String, Object> session : sessions) {
            Long id = longObject(session.get("id"));
            if (id == null || id.longValue() == rootSessionId) continue;
            ObserveNode node = ObserveNode.fromSubagent(session, rootSessionId);
            int insertAt = nodes.size();
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).sessionRowId == id.longValue() && nodes.get(i).depth >= 1) {
                    insertAt = i;
                    break;
                }
            }
            nodes.add(insertAt, node);
        }
        return nodes;
    }

    private List<Map<String, Object>> buildEdges(List<ObserveNode> nodes) {
        List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        ObserveNode previous = null;
        for (ObserveNode node : nodes) {
            if (previous != null && previous.sessionRowId == node.sessionRowId && previous.turnId != null
                    && previous.turnId.equals(node.turnId)) {
                edges.add(edge(previous.nodeId, node.nodeId, "sequence"));
            }
            if (previous != null && previous.kindEquals("tool", "skill") && "assistant".equals(node.kind)) {
                edges.add(edge(previous.nodeId, node.nodeId, "dependency"));
            }
            if (previous != null && previous.kindEquals("subagent") && previous.sessionRowId == node.sessionRowId) {
                edges.add(edge(previous.nodeId, node.nodeId, "subagent"));
            }
            previous = node;
        }
        return edges;
    }

    private static Map<String, Object> edge(String from, String to, String type) {
        Map<String, Object> edge = new LinkedHashMap<String, Object>();
        edge.put("from", from);
        edge.put("to", to);
        edge.put("type", type);
        return edge;
    }

    private List<Map<String, Object>> subagentViews(List<Map<String, Object>> sessions) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (int i = 1; i < sessions.size(); i++) {
            Map<String, Object> session = sessions.get(i);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("sessionRowId", session.get("id"));
            item.put("sessionKey", session.get("session_key"));
            item.put("title", session.get("title"));
            item.put("parentSessionKey", session.get("parent_session_key"));
            item.put("startedAt", session.get("started_at"));
            item.put("endedAt", session.get("ended_at"));
            item.put("depth", Integer.valueOf(intValue(session.get("depth"))));
            result.add(item);
        }
        return result;
    }

    /** Raw body fields transcript/original-session views depend on; trimmed payload. */
    private static Map<String, Object> dashboardStepPayload(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        copyIfPresent(payload, result, "text");
        copyIfPresent(payload, result, "content");
        copyIfPresent(payload, result, "args");
        copyIfPresent(payload, result, "result");
        copyIfPresent(payload, result, "name");
        copyIfPresent(payload, result, "path");
        copyIfPresent(payload, result, "role");
        copyIfPresent(payload, result, "tool_id");
        copyIfPresent(payload, result, "duration_ms");
        copyIfPresent(payload, result, "image_paths");
        copyIfPresent(payload, result, "document_paths");
        copyIfPresent(payload, result, "match");
        copyIfPresent(payload, result, "rollup");
        copyIfPresent(payload, result, "child_slug");
        copyIfPresent(payload, result, "outcome");
        copyIfPresent(payload, result, "error");
        copyIfPresent(payload, result, "usage");
        copyIfPresent(payload, result, "file_ops");
        return result;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.containsKey(key)) target.put(key, source.get(key));
    }

    private List<Map<String, Object>> turnViews(List<Map<String, Object>> turns,
                                                List<Map<String, Object>> steps) {
        Map<Long, List<Map<String, Object>>> stepsByTurn = new LinkedHashMap<Long, List<Map<String, Object>>>();
        for (Map<String, Object> step : steps) {
            Long turnId = longObject(step.get("turn_id"));
            List<Map<String, Object>> list = stepsByTurn.get(turnId);
            if (list == null) {
                list = new ArrayList<Map<String, Object>>();
                stepsByTurn.put(turnId, list);
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("type", step.get("type"));
            item.put("stepId", step.get("step_id"));
            item.put("seq", step.get("seq"));
            item.put("ts", step.get("ts"));
            item.put("skillSlug", step.get("skill_slug"));
            item.put("payload", dashboardStepPayload(asMap(parseJson(step.get("payload")))));
            list.add(item);
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> turn : turns) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("turnId", turn.get("id"));
            item.put("sessionRowId", turn.get("session_id"));
            item.put("turnIndex", turn.get("turn_index"));
            item.put("startedAt", turn.get("started_at"));
            item.put("userText", turn.get("user_text"));
            item.put("userTextTruncated", turn.get("user_text_truncated"));
            List<Map<String, Object>> turnSteps = stepsByTurn.get(longObject(turn.get("id")));
            item.put("steps", turnSteps == null ? Collections.emptyList() : turnSteps);
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> aggregateFiles(List<ObserveNode> nodes) {
        Map<String, Map<String, Object>> byPath = new LinkedHashMap<String, Map<String, Object>>();
        for (ObserveNode node : nodes) {
            for (Object itemObj : fileOps(node.payload)) {
                Map<String, Object> item = asMap(itemObj);
                if (item == null) continue;
                String path = stringOf(item.get("path"));
                if (path.isEmpty()) continue;
                Map<String, Object> row = byPath.get(path);
                if (row == null) {
                    row = new LinkedHashMap<String, Object>();
                    row.put("path", path);
                    row.put("nodeId", node.nodeId);
                    row.put("reads", Integer.valueOf(0));
                    row.put("writes", Integer.valueOf(0));
                    row.put("touches", Integer.valueOf(0));
                    row.put("kind", item.get("kind"));
                    byPath.put(path, row);
                }
                row.put("nodeId", node.nodeId);
                row.put("touches", Integer.valueOf(intValue(row.get("touches")) + 1));
                if ("write".equals(item.get("op"))) {
                    row.put("writes", Integer.valueOf(intValue(row.get("writes")) + 1));
                } else if ("read".equals(item.get("op"))) {
                    row.put("reads", Integer.valueOf(intValue(row.get("reads")) + 1));
                }
            }
        }
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>(byPath.values());
        Collections.sort(files, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Integer.compare(intValue(b.get("touches")), intValue(a.get("touches")));
            }
        });
        return files;
    }

    private Map<String, Object> aggregateStats(List<ObserveNode> nodes,
                                               List<Map<String, Object>> turns,
                                               List<Map<String, Object>> sessions) {
        long durationMs = 0L;
        long input = 0L;
        long cacheRead = 0L;
        long cacheWrite = 0L;
        long output = 0L;
        long reasoning = 0L;
        long total = 0L;
        int toolCalls = 0;
        int skillCalls = 0;
        int messages = 0;
        int reasoningBlocks = 0;
        Set<String> changedPaths = new HashSet<String>();
        long firstMs = Long.MAX_VALUE;
        long lastMs = Long.MIN_VALUE;
        Map<String, Integer> toolCounts = new LinkedHashMap<String, Integer>();
        Map<String, Integer> skillCounts = new LinkedHashMap<String, Integer>();
        for (ObserveNode node : nodes) {
            long nodeMs = parseMs(node.ts);
            if (nodeMs > 0) {
                if (nodeMs < firstMs) firstMs = nodeMs;
                if (nodeMs > lastMs) lastMs = nodeMs;
            }
            if ("tool".equals(node.kind)) {
                toolCalls += 1;
                String name = stringOf(node.payload.get("name"));
                if (!name.isEmpty()) toolCounts.put(name, Integer.valueOf(intValue(toolCounts.get(name)) + 1));
            }
            // Skill call口径：调用/载入/工具读 SKILL.md 均计入；读「参考资料」不计。
            if (!ObservationSkillInference.isReferenceMaterial(node.kind, node.payload)) {
                String skillName = skillCallDisplayName(node.kind, node.payload, node.title);
                if (!skillName.isEmpty()) {
                    skillCalls += 1;
                    skillCounts.put(skillName, Integer.valueOf(intValue(skillCounts.get(skillName)) + 1));
                }
            }
            if ("assistant".equals(node.kind) || "user".equals(node.kind)) messages += 1;
            if ("reasoning".equals(node.kind)) reasoningBlocks += 1;
            for (Object itemObj : fileOps(node.payload)) {
                Map<String, Object> item = asMap(itemObj);
                if (item == null) continue;
                String path = stringOf(item.get("path"));
                if (!path.isEmpty()) changedPaths.add(path);
            }
            Map<String, Object> usage = asMap(node.payload.get("usage"));
            if (usage == null || !"direct".equals(node.usageSource)) continue;
            input += longValue(usage.get("input_tokens"));
            cacheRead += longValue(usage.get("cache_read_input_tokens"));
            cacheWrite += longValue(usage.get("cache_creation_input_tokens"));
            output += longValue(usage.get("output_tokens"));
            reasoning += longValue(usage.get("reasoning_output_tokens"));
            total += longValue(usage.get("total_tokens"));
        }
        if (firstMs != Long.MAX_VALUE && lastMs >= firstMs) durationMs = lastMs - firstMs;
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("durationMs", Long.valueOf(durationMs));
        stats.put("messageCount", Integer.valueOf(messages));
        stats.put("toolCallCount", Integer.valueOf(toolCalls));
        stats.put("skillCallCount", Integer.valueOf(skillCalls));
        stats.put("changedFileCount", Integer.valueOf(changedPaths.size()));
        stats.put("reasoningCount", Integer.valueOf(reasoningBlocks));
        stats.put("sessionCount", Integer.valueOf(sessions.size()));
        stats.put("turnCount", Integer.valueOf(turns.size()));
        stats.put("inputTokens", Long.valueOf(input));
        stats.put("cacheReadTokens", Long.valueOf(cacheRead));
        stats.put("cacheWriteTokens", Long.valueOf(cacheWrite));
        stats.put("outputTokens", Long.valueOf(output));
        stats.put("reasoningTokens", Long.valueOf(reasoning));
        stats.put("totalTokens", Long.valueOf(total));
        stats.put("toolCounts", toolCounts);
        stats.put("skillCounts", skillCounts);
        return stats;
    }

    private static Map<String, Object> sessionView(Map<String, Object> session, int subagentCount) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", session.get("id"));
        view.put("sessionKey", session.get("session_key"));
        view.put("parentSessionKey", session.get("parent_session_key"));
        view.put("title", session.get("title"));
        view.put("clientName", session.get("client_name"));
        view.put("clientId", session.get("client_id"));
        view.put("hostname", session.get("hostname"));
        view.put("startedAt", session.get("started_at"));
        view.put("endedAt", session.get("ended_at"));
        view.put("isSubagent", Boolean.valueOf(session.get("parent_session_key") != null));
        view.put("subagentCount", Integer.valueOf(subagentCount));
        return view;
    }

    /** Fill a display title when the session row carries none (never rewrites stored rows). */
    private static void enrichSessionTitles(List<Map<String, Object>> sessions) {
        for (Map<String, Object> session : sessions) {
            String title = ObservationRepository.cleanSessionTitleText(session.get("title"));
            if (title.isEmpty()) title = ObservationRepository.cleanSessionTitleText(session.get("session_title"));
            if (title.isEmpty()) title = ObservationRepository.projectHintFromSessionKey(session.get("session_key"));
            if (title.isEmpty()) {
                String client = session.get("client_name") == null ? "会话"
                        : String.valueOf(session.get("client_name"));
                Object started = session.get("started_at");
                String day = started == null ? "" : String.valueOf(started);
                if (day.length() >= 10) day = day.substring(0, 10);
                title = day.isEmpty() ? client : client + " · " + day;
            }
            if (title.length() > 60) title = title.substring(0, 60) + "…";
            session.put("title", title);
        }
    }

    // ------------------------------------------------------------ node view model

    private static final class ObserveNode {
        private final String nodeId;
        private final String kind;
        private final long sessionRowId;
        private final Long turnId;
        private final int turnIndex;
        private final int seq;
        private final String stepId;
        private final String title;
        private final String summary;
        private final String ts;
        private final long durationMs;
        private final String durationSource;
        private final Map<String, Object> usage;
        private final String usageSource;
        private final int depth;
        private final String parentNodeId;
        private final Map<String, Object> payload;

        private ObserveNode(String nodeId, String kind, long sessionRowId, Long turnId, int turnIndex, int seq,
                            String stepId, String title, String summary, String ts, long durationMs,
                            String durationSource, Map<String, Object> usage, String usageSource, int depth,
                            String parentNodeId, Map<String, Object> payload) {
            this.nodeId = nodeId;
            this.kind = kind;
            this.sessionRowId = sessionRowId;
            this.turnId = turnId;
            this.turnIndex = turnIndex;
            this.seq = seq;
            this.stepId = stepId;
            this.title = title;
            this.summary = summary;
            this.ts = ts;
            this.durationMs = durationMs;
            this.durationSource = durationSource;
            this.usage = usage;
            this.usageSource = usageSource;
            this.depth = depth;
            this.parentNodeId = parentNodeId;
            this.payload = payload;
        }

        private static ObserveNode fromStep(long sessionRowId, Map<String, Object> session,
                                            Map<String, Object> turn, Map<String, Object> step,
                                            long rootSessionId,
                                            ObservationDashboardServiceImpl owner) {
            Map<String, Object> payload = asMap(owner.parseJson(step.get("payload")));
            String type = string(step.get("type"));
            int turnIndex = intValue(turn.get("turn_index"));
            String stepId = string(step.get("step_id"));
            int depth = isSubagent(session, rootSessionId) ? 1 : 0;
            Map<String, Object> usage = asMap(payload.get("usage"));
            if (usage == null) usage = Collections.emptyMap();
            String usageSource = usage.isEmpty() ? "none" : usageSource(usage);
            long duration = longValue(payload.get("duration_ms"));
            String durationSource = duration > 0 ? "observed" : "missing";
            return new ObserveNode(
                    ObservationNodeIdCodec.encodeStepNode(sessionRowId, turnIndex, stepId),
                    type,
                    sessionRowId,
                    longObject(turn.get("id")),
                    turnIndex,
                    intValue(step.get("seq")),
                    stepId,
                    title(type, payload, step),
                    summary(type, payload),
                    string(step.get("ts")),
                    duration,
                    durationSource,
                    usage,
                    usageSource,
                    depth,
                    null,
                    payload);
        }

        private static ObserveNode fromUserTurn(long sessionRowId, Map<String, Object> session,
                                                Map<String, Object> turn, long rootSessionId) {
            int turnIndex = intValue(turn.get("turn_index"));
            String text = string(turn.get("user_text"));
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("text", text);
            return new ObserveNode(
                    ObservationNodeIdCodec.encodeStepNode(sessionRowId, turnIndex, "user-turn-" + turnIndex),
                    "user",
                    sessionRowId,
                    longObject(turn.get("id")),
                    turnIndex,
                    0,
                    "user-turn-" + turnIndex,
                    "用户输入",
                    text,
                    string(turn.get("started_at")),
                    0L,
                    "missing",
                    Collections.<String, Object>emptyMap(),
                    "none",
                    isSubagent(session, rootSessionId) ? 1 : 0,
                    null,
                    payload);
        }

        private static ObserveNode fromSubagent(Map<String, Object> session, long rootSessionId) {
            long id = longValue(session.get("id"));
            return new ObserveNode(
                    ObservationNodeIdCodec.encodeSubagentNode(id),
                    "subagent",
                    id,
                    null,
                    0,
                    0,
                    null,
                    string(session.get("title")).isEmpty() ? "子 agent" : string(session.get("title")),
                    string(session.get("session_key")),
                    string(session.get("started_at")),
                    0L,
                    "missing",
                    Collections.<String, Object>emptyMap(),
                    "none",
                    1,
                    null,
                    Collections.<String, Object>emptyMap());
        }

        private static ObserveNode fromRow(Map<String, Object> row, ObservationDashboardServiceImpl owner) {
            Map<String, Object> payload = asMap(owner.parseJson(row.get("payload")));
            String type = string(row.get("type"));
            long sessionRowId = longValue(row.get("session_id"));
            int turnIndex = intValue(row.get("turn_index"));
            String stepId = string(row.get("step_id"));
            Map<String, Object> usage = asMap(payload.get("usage"));
            if (usage == null) usage = Collections.emptyMap();
            long duration = longValue(payload.get("duration_ms"));
            return new ObserveNode(
                    ObservationNodeIdCodec.encodeStepNode(sessionRowId, turnIndex, stepId),
                    type,
                    sessionRowId,
                    longObject(row.get("turn_id")),
                    turnIndex,
                    intValue(row.get("seq")),
                    stepId,
                    title(type, payload, row),
                    summary(type, payload),
                    string(row.get("ts")),
                    duration,
                    duration > 0 ? "observed" : "missing",
                    usage,
                    usage.isEmpty() ? "none" : usageSource(usage),
                    0,
                    null,
                    payload);
        }

        private Map<String, Object> toView() {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("nodeId", nodeId);
            view.put("kind", kind);
            view.put("sessionRowId", Long.valueOf(sessionRowId));
            view.put("turnId", turnId);
            view.put("turnIndex", Integer.valueOf(turnIndex));
            view.put("seq", Integer.valueOf(seq));
            view.put("stepId", stepId);
            view.put("title", title);
            view.put("summary", summary);
            view.put("ts", ts);
            view.put("durationMs", Long.valueOf(durationMs));
            view.put("durationSource", durationSource);
            view.put("usage", usage);
            view.put("usageSource", usageSource);
            view.put("files", nodeFiles(payload));
            view.put("depth", Integer.valueOf(depth));
            view.put("parentNodeId", parentNodeId);
            view.put("detailAvailable", Boolean.valueOf(!"subagent".equals(kind)));
            return view;
        }

        private boolean kindEquals(String... kinds) {
            for (String item : kinds) {
                if (item.equals(kind)) return true;
            }
            return false;
        }

        private static String usageSource(Map<String, Object> usage) {
            String scope = string(usage.get("scope"));
            return "message".equals(scope) ? "direct" : "inferred";
        }

        private static String title(String type, Map<String, Object> payload, Map<String, Object> source) {
            if ("user".equals(type)) return "用户输入";
            if ("assistant".equals(type)) return "助手回复";
            if ("reasoning".equals(type)) return "推理块";
            if ("skill".equals(type)) return string(payload.get("name")).isEmpty()
                    ? string(source.get("skill_slug")) : string(payload.get("name"));
            if ("system".equals(type)) return "系统指令";
            if ("document".equals(type)) return string(payload.get("path"));
            return string(payload.get("name"));
        }

        private static String summary(String type, Map<String, Object> payload) {
            if ("user".equals(type) || "assistant".equals(type) || "reasoning".equals(type)) {
                return excerpt(string(payload.get("text")), 240);
            }
            if ("system".equals(type)) return excerpt(string(payload.get("text")), 240);
            if ("document".equals(type)) return string(payload.get("path"));
            if ("tool".equals(type) || "skill".equals(type)) return excerpt(string(payload.get("result")), 240);
            return "";
        }

        private static boolean isSubagent(Map<String, Object> session, long rootSessionId) {
            if (session == null) return false;
            Long id = longObject(session.get("id"));
            return id != null && id.longValue() != rootSessionId;
        }
    }

    /** Skill call display name: leaf skill name first; reference material returns empty. */
    private static String skillCallDisplayName(String kind, Map<String, Object> payload, String fallbackTitle) {
        if ("skill".equalsIgnoreCase(kind)) {
            String name = stringOf(payload.get("name"));
            if (!name.isEmpty()) return name;
            if (fallbackTitle != null && !fallbackTitle.isEmpty()) return fallbackTitle;
        }
        ObservationSkillInference.SkillIdentity identity = ObservationSkillInference.infer(kind, payload);
        if (identity != null) {
            return identity.displayName();
        }
        Object match = payload.get("match");
        if (match != null && !String.valueOf(match).isEmpty()) {
            String name = stringOf(payload.get("name"));
            if (name.isEmpty()) name = stringOf(payload.get("skill_slug"));
            if (name.isEmpty() && fallbackTitle != null) name = fallbackTitle;
            return name;
        }
        return "";
    }

    // ------------------------------------------------------------ file ops

    /**
     * Prefer file_ops captured by newer observers. Older sessions only kept raw tool
     * args, so derive the same lightweight read/write view at aggregation time.
     */
    private static List<?> fileOps(Map<String, Object> payload) {
        if (payload == null) return Collections.emptyList();
        Object captured = payload.get("file_ops");
        if (captured instanceof List && !((List<?>) captured).isEmpty()) return (List<?>) captured;

        String tool = stringOf(payload.get("name")).toLowerCase(Locale.ROOT);
        String op = fileOp(tool, payload.get("args"));
        if (op == null && !payload.containsKey("args")) return Collections.emptyList();

        Map<String, Map<String, Object>> byPath = new LinkedHashMap<String, Map<String, Object>>();
        collectPaths(payload.get("args"), byPath);
        if (byPath.isEmpty()) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : byPath.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("path", entry.getKey());
            item.put("op", op == null ? "unknown" : op);
            item.put("kind", fileKind(entry.getKey()));
            result.add(item);
            if (result.size() >= 50) break;
        }
        return result;
    }

    /** Dashboard nodes only return a few file summaries, keeping bodies small. */
    private static List<Map<String, Object>> nodeFiles(Map<String, Object> payload) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object itemObj : fileOps(payload)) {
            Map<String, Object> item = asMap(itemObj);
            if (item == null) continue;
            String path = stringOf(item.get("path"));
            if (path.isEmpty()) continue;
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("path", path);
            view.put("op", item.get("op"));
            view.put("kind", item.get("kind"));
            result.add(view);
            if (result.size() >= 12) break;
        }
        return result;
    }

    private static String fileOp(String tool, Object args) {
        if ("read".equals(tool) || "read_file".equals(tool) || "get-content".equals(tool)
                || "cat".equals(tool) || "type".equals(tool) || "grep".equals(tool)
                || "rg".equals(tool) || "glob".equals(tool) || "find".equals(tool)) {
            return "read";
        }
        if ("edit".equals(tool) || "write".equals(tool) || "multiedit".equals(tool)
                || "notebookedit".equals(tool) || "apply_patch".equals(tool)
                || "create_file".equals(tool) || "write_file".equals(tool)
                || "delete_file".equals(tool) || "remove_file".equals(tool)
                || "move_file".equals(tool) || "rename_file".equals(tool)) {
            return "write";
        }
        String command = stringOf(args).toLowerCase(Locale.ROOT);
        if (command.contains("set-content") || command.contains("add-content") || command.contains("out-file")
                || command.contains("remove-item") || command.contains("move-item") || command.contains("rename-item")
                || command.contains("sed -i") || command.contains("tee ")) {
            return "write";
        }
        if (command.contains("get-content") || command.contains("cat ") || command.contains("rg ")
                || command.contains("grep ") || command.contains("findstr ")) {
            return "read";
        }
        return null;
    }

    private static void collectPaths(Object value, Map<String, Map<String, Object>> found) {
        if (value == null) return;
        if (value instanceof Map) {
            Map<String, Object> map = asMap(value);
            if (map == null) return;
            for (String key : Arrays.asList("path", "file", "filePath", "file_path",
                    "sourcePath", "source_path", "target_file", "targetFile")) {
                Object direct = map.get(key);
                if (direct instanceof String && !((String) direct).trim().isEmpty()) {
                    addPath(((String) direct).trim(), found);
                }
            }
            for (Object nested : map.values()) collectPaths(nested, found);
            return;
        }
        if (value instanceof List) {
            for (Object item : (List<?>) value) collectPaths(item, found);
            return;
        }
        String text = String.valueOf(value);
        java.util.regex.Matcher patch = java.util.regex.Pattern
                .compile("\\*\\*\\* (?:Add|Update|Delete) File: ([^\\n\\\\\"]+)")
                .matcher(text);
        while (patch.find()) addPath(patch.group(1).trim(), found);
    }

    private static void addPath(String path, Map<String, Map<String, Object>> found) {
        if (path == null) return;
        String trimmed = path.trim();
        if (trimmed.isEmpty()) return;
        if (!found.containsKey(trimmed)) found.put(trimmed, Collections.<String, Object>emptyMap());
    }

    private static String fileKind(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg")) {
            return "image";
        }
        if (lower.endsWith(".md") || lower.endsWith(".mdx") || lower.endsWith(".txt")
                || lower.endsWith(".rst")) {
            return "document";
        }
        return "code";
    }

    // ------------------------------------------------------------ value helpers

    private Object parseJson(Object value) {
        if (value == null) return Collections.emptyMap();
        if (value instanceof Map) return value;
        String raw = value instanceof PGobject ? ((PGobject) value).getValue() : String.valueOf(value);
        return parse(raw);
    }

    private Object parse(String value) {
        if (value == null) return Collections.emptyMap();
        String raw = String.valueOf(value);
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignored) {
            try {
                return objectMapper.readValue(raw, Object.class);
            } catch (Exception ignoredAgain) {
                return raw;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) return (Map<String, Object>) value;
        return new LinkedHashMap<String, Object>();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intValue(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    private static Long longObject(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return Long.valueOf(((Number) value).longValue());
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static long parseMs(String value) {
        if (value == null) return 0L;
        String raw = value.trim();
        if (raw.isEmpty()) return 0L;
        try {
            return java.time.Instant.parse(raw).toEpochMilli();
        } catch (RuntimeException ignored) {
            // Fall through to the database timestamp formats below.
        }
        String normalized = raw.replace(' ', 'T');
        if (normalized.matches(".*[+-]\\d{2}$")) normalized = normalized + ":00";
        try {
            return java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // Fall through to a local timestamp without zone.
        }
        try {
            return java.time.LocalDateTime.parse(normalized)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String excerpt(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (text.length() <= max) return text;
        return text.substring(0, max) + "...";
    }

    private static String firstText(Map<String, Object> payload) {
        String text = string(payload.get("text"));
        if (text.isEmpty()) text = string(payload.get("content"));
        return text;
    }

    private static final class CacheEntry {
        private final Map<String, Object> value;
        private final long expiresAt;

        private CacheEntry(Map<String, Object> value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
