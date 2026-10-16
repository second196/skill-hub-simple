package com.km.skillhub.workbench;

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

/**
 * JdbcTemplate repository for skill_conversation / skill_message /
 * skill_task_event (V13). No MyBatis-Plus — matches the skill-hub-simple JDBC style.
 */
@Repository
public class SkillConversationRepository {

    private static final RowMapper<SkillConversation> CONVERSATION_ROW = (rs, i) -> {
        SkillConversation conversation = new SkillConversation();
        conversation.setId(longOrNull(rs.getObject("id")));
        conversation.setSlug(rs.getString("slug"));
        conversation.setVersionId(longOrNull(rs.getObject("version_id")));
        conversation.setTitle(rs.getString("title"));
        conversation.setCreatedBy(rs.getString("created_by"));
        conversation.setCreatedAt(toOffset(rs.getTimestamp("created_at")));
        conversation.setUpdatedAt(toOffset(rs.getTimestamp("updated_at")));
        return conversation;
    };

    private static final RowMapper<SkillMessage> MESSAGE_ROW = (rs, i) -> {
        SkillMessage message = new SkillMessage();
        message.setId(longOrNull(rs.getObject("id")));
        message.setConversationId(longOrNull(rs.getObject("conversation_id")));
        int seq = rs.getInt("seq");
        message.setSeq(Integer.valueOf(rs.wasNull() ? 0 : seq));
        message.setRole(rs.getString("role"));
        message.setMsgType(rs.getString("msg_type"));
        message.setContent(rs.getString("content"));
        message.setRelatedTaskId(longOrNull(rs.getObject("related_task_id")));
        message.setRelatedCandidateId(longOrNull(rs.getObject("related_candidate_id")));
        message.setCreatedAt(toOffset(rs.getTimestamp("created_at")));
        return message;
    };

    private static final RowMapper<SkillTaskEvent> TASK_EVENT_ROW = (rs, i) -> {
        SkillTaskEvent event = new SkillTaskEvent();
        event.setId(longOrNull(rs.getObject("id")));
        event.setTaskId(longOrNull(rs.getObject("task_id")));
        event.setStage(rs.getString("stage"));
        event.setLevel(rs.getString("level"));
        event.setPayloadJson(rs.getString("payload_json"));
        event.setCreatedAt(toOffset(rs.getTimestamp("created_at")));
        return event;
    };

    private final JdbcTemplate jdbc;

    public SkillConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- conversation

    /** Skill exists (by slug); null when missing. */
    public Long findSkillId(String slug) {
        if (slug == null || slug.trim().isEmpty()) {
            return null;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM skill WHERE slug=?", Long.class, slug.trim());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Version row's owning skill id; null when the version does not exist. */
    public Long findVersionSkillId(Long versionId) {
        if (versionId == null) {
            return null;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT skill_id FROM skill_version WHERE id=?", Long.class, versionId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public SkillConversation insertConversation(SkillConversation conversation) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO skill_conversation(slug,version_id,title,created_by,created_at,updated_at)"
                            + " VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"
                            + " RETURNING id",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, conversation.getSlug());
            ps.setLong(2, conversation.getVersionId().longValue());
            ps.setString(3, conversation.getTitle());
            ps.setString(4, conversation.getCreatedBy());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            conversation.setId(Long.valueOf(key.longValue()));
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        return conversation;
    }

    public SkillConversation findConversation(Long id) {
        if (id == null) {
            return null;
        }
        List<SkillConversation> rows = jdbc.query(
                "SELECT * FROM skill_conversation WHERE id=?", CONVERSATION_ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SkillConversation> listConversations(String slug, Long versionId) {
        if (slug == null || slug.trim().isEmpty()) {
            return Collections.emptyList();
        }
        if (versionId == null) {
            return jdbc.query(
                    "SELECT * FROM skill_conversation WHERE slug=?"
                            + " ORDER BY updated_at DESC, id DESC",
                    CONVERSATION_ROW, slug.trim());
        }
        return jdbc.query(
                "SELECT * FROM skill_conversation WHERE slug=? AND version_id=?"
                        + " ORDER BY updated_at DESC, id DESC",
                CONVERSATION_ROW, slug.trim(), versionId);
    }

    public void updateConversation(SkillConversation conversation) {
        jdbc.update(
                "UPDATE skill_conversation SET title=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                conversation.getTitle(), conversation.getId());
    }

    // ------------------------------------------------------------- message

    public SkillMessage insertMessage(SkillMessage message) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO skill_message(conversation_id,seq,role,msg_type,content,"
                            + "related_task_id,related_candidate_id,created_at)"
                            + " VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)"
                            + " RETURNING id",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, message.getConversationId().longValue());
            ps.setInt(2, message.getSeq().intValue());
            ps.setString(3, message.getRole());
            ps.setString(4, message.getMsgType());
            ps.setString(5, message.getContent());
            if (message.getRelatedTaskId() == null) {
                ps.setObject(6, null);
            } else {
                ps.setLong(6, message.getRelatedTaskId().longValue());
            }
            if (message.getRelatedCandidateId() == null) {
                ps.setObject(7, null);
            } else {
                ps.setLong(7, message.getRelatedCandidateId().longValue());
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            message.setId(Long.valueOf(key.longValue()));
        }
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        return message;
    }

    /** Messages of a conversation, seq ascending; {@code after} is an exclusive lower bound. */
    public List<SkillMessage> listMessages(Long conversationId, Integer after) {
        if (conversationId == null) {
            return Collections.emptyList();
        }
        if (after == null) {
            return jdbc.query(
                    "SELECT * FROM skill_message WHERE conversation_id=? ORDER BY seq ASC",
                    MESSAGE_ROW, conversationId);
        }
        return jdbc.query(
                "SELECT * FROM skill_message WHERE conversation_id=? AND seq > ? ORDER BY seq ASC",
                MESSAGE_ROW, conversationId, after);
    }

    /** Same-conversation max(seq)+1 (single conversation concurrency is low; no unique constraint). */
    public int nextMessageSeq(Long conversationId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM skill_message WHERE conversation_id=?",
                Integer.class, conversationId);
        return max == null ? 1 : max.intValue() + 1;
    }

    // ------------------------------------------------------------- task event

    public SkillTaskEvent insertTaskEvent(SkillTaskEvent event) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO skill_task_event(task_id,stage,level,payload_json,created_at)"
                            + " VALUES (?,?,?,?,CURRENT_TIMESTAMP)"
                            + " RETURNING id",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, event.getTaskId().longValue());
            ps.setString(2, event.getStage());
            ps.setString(3, event.getLevel());
            ps.setString(4, event.getPayloadJson());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            event.setId(Long.valueOf(key.longValue()));
        }
        if (event.getCreatedAt() == null) {
            event.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        return event;
    }

    /** Task's conversation binding (V13 column); null when the task is missing or unbound. */
    public Long findTaskConversationId(Long taskId) {
        if (taskId == null) {
            return null;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT conversation_id FROM skill_optimize_task WHERE id=?", Long.class, taskId);
        return ids.isEmpty() ? null : ids.get(0);
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
}
