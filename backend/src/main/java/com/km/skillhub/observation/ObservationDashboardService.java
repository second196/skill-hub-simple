package com.km.skillhub.observation;

import java.util.List;
import java.util.Map;

/**
 * Skill observation single-page dashboard service: graph/waterfall/stats/transcript/
 * files assembly, on-demand node detail and session full-text search.
 */
public interface ObservationDashboardService {

    /**
     * @param sessionId    root observation session row id
     * @param skillSlug    optional skill scope for the turns/nodes
     * @param versionLabel optional version narrowing (all/unknown/label)
     */
    Map<String, Object> sessionDashboard(long sessionId, String skillSlug, String versionLabel);

    /** Node detail for one dashboard node id (step or subagent). */
    Map<String, Object> dashboardNode(long sessionId, String nodeId);

    /** Session search over payload/session content. Blank query returns no hits. */
    List<Map<String, Object>> searchSessions(String slug, String versionLabel, String query, int limit);
}
