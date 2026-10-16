package com.km.skillhub.workbench;

import java.time.OffsetDateTime;

/**
 * Workbench conversation message (skill_message, V13). Events are durable in
 * the DB; SSE is only a push accelerator — clients re-sync after a disconnect
 * via messages?after=&lt;seq&gt;.
 */
public class SkillMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_PROCESS_EVENT = "process_event";
    public static final String TYPE_CANDIDATE_CARD = "candidate_card";
    public static final String TYPE_ACTION_CARD = "action_card";

    private Long id;
    private Long conversationId;
    /** Monotonic per conversation. */
    private Integer seq;
    private String role;
    private String msgType;
    /** Plain text or JSON payload depending on msgType. */
    private String content;
    private Long relatedTaskId;
    private Long relatedCandidateId;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getRelatedTaskId() { return relatedTaskId; }
    public void setRelatedTaskId(Long relatedTaskId) { this.relatedTaskId = relatedTaskId; }
    public Long getRelatedCandidateId() { return relatedCandidateId; }
    public void setRelatedCandidateId(Long relatedCandidateId) { this.relatedCandidateId = relatedCandidateId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
