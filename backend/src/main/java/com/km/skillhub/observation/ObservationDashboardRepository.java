package com.km.skillhub.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only queries behind the observation dashboard, node detail and session search.
 *
 * <p>Adapted from kmplm ObservationDashboardMapper.xml to this repo's hand-written
 * JdbcTemplate style. Long sessions load bounded: turns first (LIMIT), then steps by
 * turn ids; every list keeps the original turn/seq/id order so the UI can assemble
 * batches. Legacy skill identity (payload skill_slug / skill_version_label) is read
 * via COALESCE — raw rows are never rewritten.</p>
 */
@Repository
public class ObservationDashboardRepository {

    /** Legacy skill slug: column first, then payload history. */
    static final String LEGACY_SLUG =
            "COALESCE(NULLIF(TRIM(st.skill_slug), ''), st.payload->>'skill_slug')";
    /** Legacy skill version label: column first, then payload history. */
    static final String LEGACY_LABEL =
            "COALESCE(NULLIF(TRIM(st.skill_version_label), ''), st.payload->>'skill_version_label')";

    private final JdbcTemplate jdbc;

    public ObservationDashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The dashboard session plus every descendant subagent session (parent_session_key
     * chain, depth capped at 8), joined with client info.
     */
    public List<Map<String, Object>> dashboardSessions(long sessionId) {
        return jdbc.queryForList(
                "WITH RECURSIVE dashboard_sessions AS (" +
                        " SELECT sess.id, sess.client_row_id, sess.session_key, sess.parent_session_key," +
                        "        sess.client_name, sess.started_at, sess.ended_at, sess.title, 0 AS depth" +
                        " FROM observation_session sess WHERE sess.id = ?" +
                        " UNION ALL" +
                        " SELECT child.id, child.client_row_id, child.session_key, child.parent_session_key," +
                        "        child.client_name, child.started_at, child.ended_at, child.title, parent.depth + 1" +
                        " FROM observation_session child" +
                        " INNER JOIN dashboard_sessions parent" +
                        "   ON child.client_row_id = parent.client_row_id" +
                        "  AND child.parent_session_key = parent.session_key" +
                        " WHERE parent.depth < 8" +
                        ") SELECT ds.id, ds.client_row_id, ds.session_key, ds.parent_session_key," +
                        "       ds.client_name, ds.started_at, ds.ended_at, ds.title, ds.depth," +
                        "       c.client_id, c.hostname, c.os" +
                        " FROM dashboard_sessions ds" +
                        " INNER JOIN observation_client c ON c.id = ds.client_row_id" +
                        " ORDER BY ds.depth ASC, ds.started_at ASC NULLS LAST, ds.id ASC",
                sessionId);
    }

    /**
     * Bounded turns of the dashboard sessions. With a skill slug, only turns carrying a
     * matching skill step are kept (legacy slug/label fallback included).
     *
     * @param versionFilter null/""/"all" = no filter, "unknown" = empty label,
     *                      otherwise exact label with optional leading-v match
     */
    public List<Map<String, Object>> dashboardTurns(List<Long> sessionIds, String slug,
                                                    String versionFilter, int limit) {
        if (sessionIds == null || sessionIds.isEmpty()) return new ArrayList<Map<String, Object>>();
        StringBuilder placeholders = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        for (int i = 0; i < sessionIds.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
            args.add(sessionIds.get(i));
        }
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT t.id, t.session_id, t.turn_index, t.started_at,")
                .append(" LEFT(COALESCE(t.user_text, ''), 4000) AS user_text,")
                .append(" (COALESCE(t.user_text, '') <> '' AND length(t.user_text) > 4000) AS user_text_truncated")
                .append(" FROM observation_turn t WHERE t.session_id IN (").append(placeholders).append(')');
        if (slug != null && !slug.trim().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM observation_step st WHERE st.turn_id = t.id AND ")
                    .append(LEGACY_SLUG).append(" = ?");
            args.add(slug.trim());
            appendVersionFilter(sql, LEGACY_LABEL, versionFilter);
            sql.append(')');
        }
        sql.append(" ORDER BY t.started_at ASC NULLS LAST, t.session_id ASC, t.turn_index ASC LIMIT ?");
        args.add(Integer.valueOf(limit));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** Steps of the given turns in original order, bounded by {@code limit}. */
    public List<Map<String, Object>> dashboardSteps(List<Long> turnIds, int limit) {
        if (turnIds == null || turnIds.isEmpty()) return new ArrayList<Map<String, Object>>();
        StringBuilder placeholders = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        for (int i = 0; i < turnIds.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
            args.add(turnIds.get(i));
        }
        args.add(Integer.valueOf(limit));
        return jdbc.queryForList(
                "SELECT st.turn_id, st.step_id, st.seq, st.type, st.ts, st.skill_slug," +
                        " st.skill_version_label, st.payload," +
                        " LEFT(COALESCE(st.payload#>>'{text}', st.payload#>>'{name}', st.payload#>>'{path}', ''), 2000)" +
                        "   AS preview_text," +
                        " (COALESCE(st.payload#>>'{text}', '') <> '' AND length(st.payload#>>'{text}') > 2000)" +
                        "   AS text_truncated" +
                        " FROM observation_step st WHERE st.turn_id IN (" + placeholders + ")" +
                        " ORDER BY st.turn_id ASC, st.seq ASC, st.id ASC LIMIT ?",
                args.toArray());
    }

    /** One step row for the node detail panel, joined with its turn. */
    public Map<String, Object> dashboardNode(long sessionId, int turnIndex, String stepId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT st.turn_id, st.step_id, st.seq, st.type, st.ts, st.skill_slug," +
                        " st.skill_version_label, st.payload," +
                        " t.session_id, t.turn_index, t.started_at, t.user_text," +
                        " LEFT(COALESCE(st.payload#>>'{text}', ''), 2000) AS preview_text," +
                        " (COALESCE(st.payload#>>'{text}', '') <> '' AND length(st.payload#>>'{text}') > 2000)" +
                        "   AS text_truncated" +
                        " FROM observation_step st" +
                        " INNER JOIN observation_turn t ON t.id = st.turn_id" +
                        " WHERE t.session_id = ? AND t.turn_index = ? AND st.step_id = ? LIMIT 1",
                sessionId, Integer.valueOf(turnIndex), stepId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Session full-text search over payload content (turn user_text plus the
     * text/name/path payload fields). One hit per session, earliest match wins.
     *
     * @param slug          optional skill scope; blank = search all sessions
     * @param versionFilter optional version narrowing (only used with a slug)
     */
    public List<Map<String, Object>> searchHits(String slug, String versionFilter,
                                                String query, int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT ON (sess.id)")
                .append(" sess.id AS session_id, sess.session_key, sess.client_name, c.client_id, c.hostname,")
                .append(" COALESCE(NULLIF(TRIM(sess.title), ''), LEFT(COALESCE(TRIM(t.user_text), ''), 120), '会话') AS title,")
                .append(" LEFT(COALESCE(NULLIF(TRIM(t.user_text), ''), st.payload#>>'{text}', ''), 240) AS snippet,")
                .append(" t.started_at")
                .append(" FROM observation_session sess")
                .append(" INNER JOIN observation_client c ON c.id = sess.client_row_id")
                .append(" INNER JOIN observation_turn t ON t.session_id = sess.id")
                .append(" INNER JOIN observation_step st ON st.turn_id = t.id")
                .append(" WHERE (TRIM(t.user_text) ILIKE '%' || ? || '%'")
                .append("    OR COALESCE(st.payload#>>'{text}', '') ILIKE '%' || ? || '%'")
                .append("    OR COALESCE(st.payload#>>'{name}', '') ILIKE '%' || ? || '%'")
                .append("    OR COALESCE(st.payload#>>'{path}', '') ILIKE '%' || ? || '%')");
        List<Object> args = new ArrayList<Object>();
        args.add(query);
        args.add(query);
        args.add(query);
        args.add(query);
        if (slug != null && !slug.trim().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM observation_step skill_st WHERE skill_st.turn_id = t.id")
                    .append(" AND (skill_st.type = 'skill'")
                    .append("      OR COALESCE(skill_st.payload->>'match','') <> ''")
                    .append("      OR COALESCE(skill_st.payload->>'skill_slug','') <> ''")
                    .append("      OR COALESCE(skill_st.payload->>'skill_activations','') <> ''")
                    .append("      OR COALESCE(skill_st.payload->>'skillActivations','') <> '')")
                    .append(" AND ").append(LEGACY_SLUG.replace("st.", "skill_st.")).append(" = ?");
            args.add(slug.trim());
            appendVersionFilter(sql, LEGACY_LABEL.replace("st.", "skill_st."), versionFilter);
            sql.append(')');
        }
        sql.append(" ORDER BY sess.id, t.started_at ASC NULLS LAST, st.seq ASC LIMIT ?");
        args.add(Integer.valueOf(limit));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /**
     * Append the version predicate for a legacy label expression. Inline literals keep
     * the fragment reusable across EXISTS clauses without placeholder bookkeeping;
     * values are escaped as SQL string literals.
     */
    private static void appendVersionFilter(StringBuilder sql, String labelExpr,
                                            String versionFilter) {
        if (versionFilter == null) return;
        String value = versionFilter.trim();
        if (value.isEmpty() || "all".equalsIgnoreCase(value)) return;
        if ("unknown".equalsIgnoreCase(value)) {
            sql.append(" AND (").append(labelExpr).append(" IS NULL OR TRIM(").append(labelExpr).append(") = '')");
            return;
        }
        String label = value;
        if (label.length() > 1 && (label.charAt(0) == 'v' || label.charAt(0) == 'V')
                && Character.isDigit(label.charAt(1))) {
            label = label.substring(1);
        }
        String safe = label.replace("'", "''");
        sql.append(" AND (TRIM(").append(labelExpr).append(") = '").append(safe)
                .append("' OR TRIM(").append(labelExpr).append(") = 'v").append(safe).append("')");
    }
}
