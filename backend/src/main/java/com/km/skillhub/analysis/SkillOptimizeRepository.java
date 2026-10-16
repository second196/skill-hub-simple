package com.km.skillhub.analysis;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JdbcTemplate repository for skill optimize task / candidate / react step tables.
 * No MyBatis-Plus — matches the skill-hub-simple JDBC style.
 */
@Repository
public class SkillOptimizeRepository {

    private static final RowMapper<SkillOptimizeTask> TASK_ROW = (rs, i) -> {
        SkillOptimizeTask task = new SkillOptimizeTask();
        task.setId(longOrNull(rs.getObject("id")));
        task.setSkillId(longOrNull(rs.getObject("skill_id")));
        task.setVersionId(longOrNull(rs.getObject("version_id")));
        task.setMode(rs.getString("mode"));
        task.setStatus(rs.getString("status"));
        task.setPriorityListJson(rs.getString("priority_list_json"));
        task.setInputsJson(rs.getString("inputs_json"));
        task.setFailReason(rs.getString("fail_reason"));
        task.setRunMode(rs.getString("run_mode"));
        task.setTargetIssueIdsJson(rs.getString("target_issue_ids_json"));
        task.setProgressStage(rs.getString("progress_stage"));
        int percent = rs.getInt("progress_percent");
        task.setProgressPercent(Integer.valueOf(rs.wasNull() ? 0 : percent));
        task.setProgressMessage(rs.getString("progress_message"));
        task.setTargetScopeJson(rs.getString("target_scope_json"));
        task.setReactStateJson(rs.getString("react_state_json"));
        task.setReactMode(rs.getString("react_mode"));
        task.setEngine(rs.getString("engine"));
        task.setConversationId(longOrNull(rs.getObject("conversation_id")));
        task.setCreatedBy(rs.getString("created_by"));
        task.setCreatedAt(toOffset(rs.getTimestamp("created_at")));
        task.setUpdatedAt(toOffset(rs.getTimestamp("updated_at")));
        return task;
    };

    private static final RowMapper<SkillOptimizeCandidate> CANDIDATE_ROW = (rs, i) -> {
        SkillOptimizeCandidate candidate = new SkillOptimizeCandidate();
        candidate.setId(longOrNull(rs.getObject("id")));
        candidate.setTaskId(longOrNull(rs.getObject("task_id")));
        candidate.setSummary(rs.getString("summary"));
        candidate.setDiffText(rs.getString("diff_text"));
        candidate.setEditsJson(rs.getString("edits_json"));
        candidate.setGatesJson(rs.getString("gates_json"));
        candidate.setPairedJson(rs.getString("paired_json"));
        candidate.setDecision(rs.getString("decision"));
        candidate.setDecisionReason(rs.getString("decision_reason"));
        candidate.setDecidedBy(rs.getString("decided_by"));
        candidate.setDecidedAt(toOffset(rs.getTimestamp("decided_at")));
        candidate.setResultVersionId(longOrNull(rs.getObject("result_version_id")));
        candidate.setResultVersionLabel(rs.getString("result_version_label"));
        candidate.setCoverageJson(rs.getString("coverage_json"));
        candidate.setEvidenceSummaryJson(rs.getString("evidence_summary_json"));
        boolean truncated = rs.getBoolean("truncated");
        candidate.setTruncated(Boolean.valueOf(!rs.wasNull() && truncated));
        candidate.setCreatedAt(toOffset(rs.getTimestamp("created_at")));
        candidate.setUpdatedAt(toOffset(rs.getTimestamp("updated_at")));
        return candidate;
    };

    private static final RowMapper<SkillReactStep> STEP_ROW = (rs, i) -> {
        SkillReactStep step = new SkillReactStep();
        step.setId(longOrNull(rs.getObject("id")));
        step.setTaskId(longOrNull(rs.getObject("task_id")));
        int stepNo = rs.getInt("step_no");
        step.setStepNo(Integer.valueOf(rs.wasNull() ? 0 : stepNo));
        step.setThought(rs.getString("thought"));
        step.setActionType(rs.getString("action_type"));
        step.setActionJson(rs.getString("action_json"));
        step.setObservationJson(rs.getString("observation_json"));
        long duration = rs.getLong("duration_ms");
        step.setDurationMs(rs.wasNull() ? null : Long.valueOf(duration));
        int tokens = rs.getInt("token_estimate");
        step.setTokenEstimate(rs.wasNull() ? null : Integer.valueOf(tokens));
        step.setCreatedAt(toOffset(rs.getTimestamp("created_at")));
        return step;
    };

    private final JdbcTemplate jdbc;

    public SkillOptimizeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- task

    public Long countActiveTasks(Long skillId, Long versionId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM skill_optimize_task"
                        + " WHERE skill_id=? AND version_id=?"
                        + " AND status IN ('queued','running','waiting_input')",
                Long.class, skillId, versionId);
        return count == null ? Long.valueOf(0L) : count;
    }

    public SkillOptimizeTask insertTask(SkillOptimizeTask task) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO skill_optimize_task("
                            + "skill_id,version_id,mode,status,priority_list_json,inputs_json,"
                            + "fail_reason,run_mode,target_issue_ids_json,progress_stage,progress_percent,"
                            + "progress_message,target_scope_json,react_state_json,react_mode,engine,"
                            + "conversation_id,created_by,created_at,updated_at"
                            + ") VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"
                            + " RETURNING id",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, task.getSkillId().longValue());
            ps.setLong(2, task.getVersionId().longValue());
            ps.setString(3, task.getMode());
            ps.setString(4, task.getStatus());
            ps.setString(5, nvl(task.getPriorityListJson(), "[]"));
            ps.setString(6, nvl(task.getInputsJson(), "{}"));
            ps.setString(7, task.getFailReason());
            ps.setString(8, nvl(task.getRunMode(), "auto"));
            ps.setString(9, task.getTargetIssueIdsJson());
            ps.setString(10, nvl(task.getProgressStage(), "queued"));
            ps.setInt(11, task.getProgressPercent() == null ? 0 : task.getProgressPercent().intValue());
            ps.setString(12, nvl(task.getProgressMessage(), "等待后台执行"));
            ps.setString(13, task.getTargetScopeJson());
            ps.setString(14, task.getReactStateJson());
            ps.setString(15, task.getReactMode());
            ps.setString(16, nvl(task.getEngine(), "react"));
            if (task.getConversationId() == null) {
                ps.setObject(17, null);
            } else {
                ps.setLong(17, task.getConversationId().longValue());
            }
            ps.setString(18, task.getCreatedBy());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            task.setId(Long.valueOf(key.longValue()));
        }
        return task;
    }

    public void updateTask(SkillOptimizeTask task) {
        jdbc.update(
                "UPDATE skill_optimize_task SET status=?,progress_stage=?,progress_percent=?,progress_message=?,"
                        + "fail_reason=?,priority_list_json=?::jsonb,inputs_json=?::jsonb,"
                        + "target_issue_ids_json=?,"
                        + "target_scope_json=?,react_state_json=?,react_mode=?,engine=?,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                task.getStatus(),
                task.getProgressStage(),
                task.getProgressPercent() == null ? Integer.valueOf(0) : task.getProgressPercent(),
                task.getProgressMessage(),
                task.getFailReason(),
                nvl(task.getPriorityListJson(), "[]"),
                nvl(task.getInputsJson(), "{}"),
                task.getTargetIssueIdsJson(),
                task.getTargetScopeJson(),
                task.getReactStateJson(),
                task.getReactMode(),
                task.getEngine(),
                task.getId());
    }

    public SkillOptimizeTask findTask(Long id) {
        if (id == null) {
            return null;
        }
        List<SkillOptimizeTask> rows = jdbc.query(
                "SELECT * FROM skill_optimize_task WHERE id=?", TASK_ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SkillOptimizeTask> listTasksBySkill(Long skillId, String mode) {
        if (skillId == null) {
            return Collections.emptyList();
        }
        if (mode == null || mode.trim().isEmpty()) {
            return jdbc.query(
                    "SELECT * FROM skill_optimize_task WHERE skill_id=? ORDER BY id DESC",
                    TASK_ROW, skillId);
        }
        return jdbc.query(
                "SELECT * FROM skill_optimize_task WHERE skill_id=? AND mode=? ORDER BY id DESC",
                TASK_ROW, skillId, mode.trim());
    }

    public List<SkillOptimizeTask> findQueuedTasks(int limit) {
        return jdbc.query(
                "SELECT * FROM skill_optimize_task WHERE status='queued' ORDER BY id ASC LIMIT ?",
                TASK_ROW, Integer.valueOf(Math.max(1, limit)));
    }

    public List<SkillOptimizeTask> findRunningReactTasks(int limit) {
        return jdbc.query(
                "SELECT * FROM skill_optimize_task WHERE status='running' AND engine='react'"
                        + " ORDER BY id ASC LIMIT ?",
                TASK_ROW, Integer.valueOf(Math.max(1, limit)));
    }

    /** Latest waiting_input task bound to a conversation; null when none. */
    public SkillOptimizeTask findWaitingInputTask(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        List<SkillOptimizeTask> rows = jdbc.query(
                "SELECT * FROM skill_optimize_task WHERE conversation_id=? AND status='waiting_input'"
                        + " ORDER BY id DESC LIMIT 1",
                TASK_ROW, conversationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------- candidate

    public SkillOptimizeCandidate insertCandidate(SkillOptimizeCandidate candidate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO skill_optimize_candidate("
                            + "task_id,summary,diff_text,edits_json,gates_json,paired_json,decision,"
                            + "decision_reason,decided_by,decided_at,result_version_id,result_version_label,"
                            + "coverage_json,evidence_summary_json,truncated,created_at,updated_at"
                            + ") VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"
                            + " RETURNING id",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, candidate.getTaskId().longValue());
            ps.setString(2, candidate.getSummary());
            ps.setString(3, candidate.getDiffText());
            ps.setString(4, nvl(candidate.getEditsJson(), "[]"));
            ps.setString(5, nvl(candidate.getGatesJson(), "{}"));
            ps.setString(6, nvl(candidate.getPairedJson(), "{}"));
            ps.setString(7, nvl(candidate.getDecision(), "pending"));
            ps.setString(8, candidate.getDecisionReason());
            ps.setString(9, candidate.getDecidedBy());
            if (candidate.getDecidedAt() == null) {
                ps.setObject(10, null);
            } else {
                ps.setTimestamp(10, Timestamp.from(candidate.getDecidedAt().toInstant()));
            }
            if (candidate.getResultVersionId() == null) {
                ps.setObject(11, null);
            } else {
                ps.setLong(11, candidate.getResultVersionId().longValue());
            }
            ps.setString(12, candidate.getResultVersionLabel());
            ps.setString(13, candidate.getCoverageJson());
            ps.setString(14, candidate.getEvidenceSummaryJson());
            ps.setBoolean(15, candidate.getTruncated() != null && candidate.getTruncated().booleanValue());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            candidate.setId(Long.valueOf(key.longValue()));
        }
        return candidate;
    }

    public void updateCandidate(SkillOptimizeCandidate candidate) {
        jdbc.update(
                "UPDATE skill_optimize_candidate SET summary=?,diff_text=?,edits_json=?::jsonb,"
                        + "gates_json=?::jsonb,paired_json=?::jsonb,decision=?,decision_reason=?,decided_by=?,"
                        + "decided_at=?,"
                        + "result_version_id=?,result_version_label=?,coverage_json=?,evidence_summary_json=?,"
                        + "truncated=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                candidate.getSummary(),
                candidate.getDiffText(),
                nvl(candidate.getEditsJson(), "[]"),
                nvl(candidate.getGatesJson(), "{}"),
                nvl(candidate.getPairedJson(), "{}"),
                candidate.getDecision(),
                candidate.getDecisionReason(),
                candidate.getDecidedBy(),
                candidate.getDecidedAt() == null ? null : Timestamp.from(candidate.getDecidedAt().toInstant()),
                candidate.getResultVersionId(),
                candidate.getResultVersionLabel(),
                candidate.getCoverageJson(),
                candidate.getEvidenceSummaryJson(),
                Boolean.valueOf(candidate.getTruncated() != null && candidate.getTruncated().booleanValue()),
                candidate.getId());
    }

    public SkillOptimizeCandidate findCandidate(Long id) {
        if (id == null) {
            return null;
        }
        List<SkillOptimizeCandidate> rows = jdbc.query(
                "SELECT * FROM skill_optimize_candidate WHERE id=?", CANDIDATE_ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SkillOptimizeCandidate> listCandidates(Long taskId) {
        if (taskId == null) {
            return Collections.emptyList();
        }
        return jdbc.query(
                "SELECT * FROM skill_optimize_candidate WHERE task_id=? ORDER BY id DESC",
                CANDIDATE_ROW, taskId);
    }

    // ------------------------------------------------------------- react steps

    public void insertReactStep(SkillReactStep step) {
        jdbc.update(
                "INSERT INTO skill_react_step(task_id,step_no,thought,action_type,action_json,"
                        + "observation_json,duration_ms,token_estimate,created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                step.getTaskId(),
                step.getStepNo(),
                step.getThought(),
                step.getActionType(),
                step.getActionJson(),
                step.getObservationJson(),
                step.getDurationMs(),
                step.getTokenEstimate());
    }

    public List<SkillReactStep> listReactSteps(Long taskId, Long after, int size) {
        if (taskId == null) {
            return Collections.emptyList();
        }
        int limit = size <= 0 ? 500 : Math.min(size, 2000);
        if (after == null) {
            return jdbc.query(
                    "SELECT * FROM skill_react_step WHERE task_id=? ORDER BY step_no ASC LIMIT ?",
                    STEP_ROW, taskId, Integer.valueOf(limit));
        }
        return jdbc.query(
                "SELECT * FROM skill_react_step WHERE task_id=? AND step_no > ? ORDER BY step_no ASC LIMIT ?",
                STEP_ROW, taskId, after, Integer.valueOf(limit));
    }

    public boolean hasReactSteps(Long taskId) {
        if (taskId == null) {
            return false;
        }
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM skill_react_step WHERE task_id=? LIMIT 1", Long.class, taskId);
        return count != null && count.longValue() > 0L;
    }

    // ------------------------------------------------------------- quality config

    /** Read a numeric config from skill_quality_config (T12 tables); null when missing/unreadable. */
    public Double findQualityConfigNumber(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        try {
            List<Object> rows = jdbc.queryForList(
                    "SELECT config_value::text FROM skill_quality_config WHERE config_key=?",
                    Object.class, key.trim());
            if (rows.isEmpty() || rows.get(0) == null) {
                return null;
            }
            String text = String.valueOf(rows.get(0)).trim().replace("\"", "");
            return Double.valueOf(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------- skill version files

    public List<Map<String, Object>> listVersionFiles(Long versionId) {
        if (versionId == null) {
            return Collections.emptyList();
        }
        return jdbc.queryForList(
                "SELECT id,path,content,content_type,size_bytes,content_digest FROM skill_file"
                        + " WHERE version_id=? ORDER BY path", versionId);
    }

    public List<String> listVersionLabels(Long skillId) {
        if (skillId == null) {
            return Collections.emptyList();
        }
        return jdbc.queryForList(
                "SELECT version_label FROM skill_version WHERE skill_id=? ORDER BY created_at DESC,id DESC",
                String.class, skillId);
    }

    public Long insertSkillVersion(Long skillId, String versionLabel, String digest) {
        // V8 drops version_digest; only (skill_id, version_label) are required.
        return jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, skillId, versionLabel);
    }

    public void insertSkillFile(Long versionId, String path, byte[] content,
                                String contentType, long sizeBytes, String contentDigest) {
        jdbc.update(
                "INSERT INTO skill_file(version_id,path,content,content_type,size_bytes,content_digest)"
                        + " VALUES (?,?,?,?,?,?)",
                versionId, path, content, contentType, Long.valueOf(sizeBytes), contentDigest);
    }

    public int countVersionFiles(Long versionId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM skill_file WHERE version_id=?", Long.class, versionId);
        return count == null ? 0 : count.intValue();
    }

    // ------------------------------------------------------------- helpers

    private static Long longOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        return null;
    }

    private static OffsetDateTime toOffset(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
    }

    private static String nvl(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
