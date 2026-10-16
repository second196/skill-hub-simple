package com.km.skillhub.workbench;

import java.util.List;

/**
 * Skill workbench conversation service: conversations bind Skill@version;
 * messages are durable in the DB keyed by seq.
 *
 * <p>sendMessage only persists the user message and returns it (intent-driven
 * replies are a later concern; the workbench UI drives analysis/optimize via
 * their explicit APIs).</p>
 */
public interface SkillConversationService {

    /**
     * Create a conversation: validate slug@version exists; write the welcome
     * message (assistant/text) and the quick-action card (system/action_card).
     */
    SkillConversation createConversation(String slug, Long versionId);

    /** Conversation history for a slug (+optional versionId), updated_at descending. */
    List<SkillConversation> listConversations(String slug, Long versionId);

    /**
     * Messages of a conversation, seq ascending.
     *
     * @param after exclusive lower bound: only messages with seq &gt; after; null → all
     */
    List<SkillMessage> listMessages(Long conversationId, Integer after);

    /**
     * Persist a user message: next seq, first-user-input title backfill,
     * updated_at refresh, SSE broadcast; returns the user message.
     */
    SkillMessage sendMessage(Long conversationId, String content);
}
