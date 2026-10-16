package com.km.skillhub.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.skill.SkillApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill workbench conversation implementation.
 *
 * <p>On create the server writes the welcome message (assistant/text) and the
 * quick-action card (system/action_card, content is JSON with the analyze /
 * optimize_issues / describe_goal actions). seq uses same-conversation
 * max(seq)+1 (low per-conversation concurrency; V13 has no
 * (conversation_id, seq) unique constraint). sendMessage persists the user
 * message, backfills the title from the first user input, refreshes
 * updated_at, and broadcasts via SSE (DB remains the source of truth).</p>
 */
@Service
public class SkillConversationServiceImpl implements SkillConversationService {

    public static final String CODE_CONVERSATION_NOT_FOUND = "CONVERSATION_NOT_FOUND";

    /** Title summary cap (first user input, truncated). */
    private static final int TITLE_MAX = 60;

    private final SkillConversationRepository repository;
    private final ObjectMapper objectMapper;
    /** Optional: old test constructors may skip SSE broadcast; broadcast failures never break the flow. */
    private final SkillSseHub sseHub;

    public SkillConversationServiceImpl(SkillConversationRepository repository,
                                        ObjectMapper objectMapper) {
        this(repository, objectMapper, null);
    }

    @Autowired
    public SkillConversationServiceImpl(SkillConversationRepository repository,
                                        ObjectMapper objectMapper,
                                        SkillSseHub sseHub) {
        this.repository = repository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.sseHub = sseHub;
    }

    @Override
    @Transactional
    public SkillConversation createConversation(String slug, Long versionId) {
        if (slug == null || slug.trim().isEmpty()) {
            throw new SkillApiException("slug 不能为空", SkillApiException.CODE_VALIDATION_ERROR);
        }
        if (versionId == null) {
            throw new SkillApiException("versionId 不能为空", SkillApiException.CODE_VALIDATION_ERROR);
        }
        String normalizedSlug = slug.trim();
        Long skillId = repository.findSkillId(normalizedSlug);
        if (skillId == null) {
            throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
        }
        Long versionSkillId = repository.findVersionSkillId(versionId);
        if (versionSkillId == null || !skillId.equals(versionSkillId)) {
            throw new SkillApiException("Skill版本不存在", SkillApiException.CODE_VERSION_NOT_FOUND);
        }

        SkillConversation conversation = new SkillConversation();
        conversation.setSlug(normalizedSlug);
        conversation.setVersionId(versionId);
        SkillConversation saved = repository.insertConversation(conversation);

        // Welcome message (assistant / text) seq=1
        insertMessage(saved.getId(), Integer.valueOf(1),
                SkillMessage.ROLE_ASSISTANT, SkillMessage.TYPE_TEXT,
                "你好！这里是 " + normalizedSlug + " 的分析优化工作台。你可以让我分析这个技能、修已发现的问题，"
                        + "或直接说说想怎么改。");
        // Quick-action card (system / action_card) seq=2, content is JSON
        insertMessage(saved.getId(), Integer.valueOf(2),
                SkillMessage.ROLE_SYSTEM, SkillMessage.TYPE_ACTION_CARD,
                buildActionCardContent());
        return saved;
    }

    @Override
    public List<SkillConversation> listConversations(String slug, Long versionId) {
        return repository.listConversations(slug, versionId);
    }

    @Override
    public List<SkillMessage> listMessages(Long conversationId, Integer after) {
        return repository.listMessages(conversationId, after);
    }

    @Override
    @Transactional
    public SkillMessage sendMessage(Long conversationId, String content) {
        if (conversationId == null) {
            throw new SkillApiException("conversationId 不能为空", SkillApiException.CODE_VALIDATION_ERROR);
        }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new SkillApiException("content 不能为空", SkillApiException.CODE_VALIDATION_ERROR);
        }
        SkillConversation conversation = repository.findConversation(conversationId);
        if (conversation == null) {
            throw new SkillApiException("会话不存在", CODE_CONVERSATION_NOT_FOUND);
        }

        SkillMessage message = insertMessage(conversationId,
                Integer.valueOf(repository.nextMessageSeq(conversationId)),
                SkillMessage.ROLE_USER, SkillMessage.TYPE_TEXT, text);

        if (conversation.getTitle() == null || conversation.getTitle().trim().isEmpty()) {
            conversation.setTitle(truncate(text));
        }
        repository.updateConversation(conversation);
        return message;
    }

    // ------------------------------------------------------------- helpers

    private SkillMessage insertMessage(Long conversationId, Integer seq, String role,
                                       String msgType, String content) {
        SkillMessage message = new SkillMessage();
        message.setConversationId(conversationId);
        message.setSeq(seq);
        message.setRole(role);
        message.setMsgType(msgType);
        message.setContent(content);
        SkillMessage saved = repository.insertMessage(message);
        // Broadcast after persist: SSE only accelerates; re-sync by seq on disconnect
        broadcast(saved);
        return saved;
    }

    /** Broadcast to the conversation's SSE connections; failure never breaks the main flow. */
    private void broadcast(SkillMessage message) {
        if (sseHub == null || message == null || message.getConversationId() == null) {
            return;
        }
        try {
            sseHub.broadcast(message.getConversationId(), message);
        } catch (RuntimeException ex) {
            // Broadcast failure never blocks persistence; clients re-sync by seq.
        }
    }

    /** Quick-action card content: three actions, plain-language copy. */
    private String buildActionCardContent() {
        List<Map<String, String>> actions = new ArrayList<Map<String, String>>();
        actions.add(action("analyze", "帮我分析这个技能",
                "跑一遍质量检查，看看这个技能有哪些问题"));
        actions.add(action("optimize_issues", "把发现的问题修一修",
                "针对已发现的问题生成优化方案，你确认后再改"));
        actions.add(action("describe_goal", "我说说想怎么改",
                "用自己的话描述想要的效果，我来整理成优化目标"));
        Map<String, Object> card = new LinkedHashMap<String, Object>();
        card.put("actions", actions);
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception ex) {
            // Fallback: a serialization failure must not block conversation creation
            return "{\"actions\":[{\"action\":\"analyze\",\"label\":\"帮我分析这个技能\"},"
                    + "{\"action\":\"optimize_issues\",\"label\":\"把发现的问题修一修\"},"
                    + "{\"action\":\"describe_goal\",\"label\":\"我说说想怎么改\"}]}";
        }
    }

    private static Map<String, String> action(String action, String label, String hint) {
        Map<String, String> item = new LinkedHashMap<String, String>();
        item.put("action", action);
        item.put("label", label);
        item.put("hint", hint);
        return item;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= TITLE_MAX) {
            return text;
        }
        return text.substring(0, TITLE_MAX);
    }
}
