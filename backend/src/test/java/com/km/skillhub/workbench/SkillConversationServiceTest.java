package com.km.skillhub.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.skill.SkillApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conversation lifecycle against local PG (skillhub_t10_test):
 * welcome + action card on create, list ordering, message cursor (after=seq),
 * sendMessage seq/title/updated_at behavior, validation and not-found errors.
 * Fixtures use unique slugs so concurrent runs never collide.
 */
class SkillConversationServiceTest {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/skillhub_t10_test";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "123456";

    private static JdbcTemplate jdbc;
    private static SkillConversationRepository repository;
    private static ObjectMapper mapper;

    /** Recording SSE hub stub: captures broadcasts without any container. */
    private static final class RecordingHub implements SkillSseHub {
        final List<SkillMessage> broadcasts = new CopyOnWriteArrayList<SkillMessage>();

        @Override
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter register(Long conversationId) {
            return new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(1000L);
        }

        @Override
        public void unregister(Long conversationId,
                               org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        }

        @Override
        public void broadcast(Long conversationId, SkillMessage message) {
            broadcasts.add(message);
        }

        @Override
        public int connectionCount(Long conversationId) {
            return 0;
        }
    }

    private RecordingHub hub;
    private SkillConversationServiceImpl service;

    private String slug;
    private Long skillId;
    private Long versionId;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource ds = new DriverManagerDataSource(DB_URL, DB_USER, DB_PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.queryForObject("SELECT count(*) FROM skill_conversation", Integer.class);
        repository = new SkillConversationRepository(jdbc);
        mapper = new ObjectMapper();
    }

    @AfterAll
    static void cleanup() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM skill_optimize_task WHERE created_by LIKE 't14-conv-%'");
            jdbc.update("DELETE FROM skill_conversation WHERE slug LIKE 't14-conv-%'");
            jdbc.update("DELETE FROM skill WHERE slug LIKE 't14-conv-%'");
        }
    }

    @BeforeEach
    void seedSkill() {
        hub = new RecordingHub();
        service = new SkillConversationServiceImpl(repository, mapper, hub);
        slug = "t14-conv-" + UUID.randomUUID();
        skillId = jdbc.queryForObject(
                "INSERT INTO skill(slug,name,description,category) VALUES (?,?,?,?) RETURNING id",
                Long.class, slug, "T14 Conv Skill", "t14", "研发");
        versionId = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, skillId, "1.0.0");
    }

    @Test
    void createConversationWritesWelcomeAndActionCard() {
        SkillConversation conversation = service.createConversation(slug, versionId);

        assertNotNull(conversation.getId());
        assertEquals(slug, conversation.getSlug());
        assertEquals(versionId, conversation.getVersionId());
        assertNotNull(conversation.getCreatedAt());
        assertNotNull(conversation.getUpdatedAt());

        List<SkillMessage> messages = service.listMessages(conversation.getId(), null);
        assertEquals(2, messages.size());

        SkillMessage welcome = messages.get(0);
        assertEquals(Integer.valueOf(1), welcome.getSeq());
        assertEquals("assistant", welcome.getRole());
        assertEquals("text", welcome.getMsgType());
        assertTrue(welcome.getContent().contains(slug));
        assertTrue(welcome.getContent().contains("分析优化工作台"));

        SkillMessage card = messages.get(1);
        assertEquals(Integer.valueOf(2), card.getSeq());
        assertEquals("system", card.getRole());
        assertEquals("action_card", card.getMsgType());
        assertTrue(card.getContent().contains("\"action\":\"analyze\""));
        assertTrue(card.getContent().contains("optimize_issues"));
        assertTrue(card.getContent().contains("describe_goal"));

        // Both welcome messages were broadcast on create
        assertEquals(2, hub.broadcasts.size());
    }

    @Test
    void createConversationValidatesInput() {
        SkillApiException blankSlug = assertThrows(SkillApiException.class,
                () -> service.createConversation("  ", versionId));
        assertEquals(SkillApiException.CODE_VALIDATION_ERROR, blankSlug.getCode());

        SkillApiException nullVersion = assertThrows(SkillApiException.class,
                () -> service.createConversation(slug, null));
        assertEquals(SkillApiException.CODE_VALIDATION_ERROR, nullVersion.getCode());

        SkillApiException missingSkill = assertThrows(SkillApiException.class,
                () -> service.createConversation("no-such-skill-" + UUID.randomUUID(), versionId));
        assertEquals(SkillApiException.CODE_SKILL_NOT_FOUND, missingSkill.getCode());

        // versionId belonging to a different skill
        String otherSlug = "t14-conv-other-" + UUID.randomUUID();
        Long otherSkillId = jdbc.queryForObject(
                "INSERT INTO skill(slug,name,description,category) VALUES (?,?,?,?) RETURNING id",
                Long.class, otherSlug, "Other", "t14", "研发");
        Long otherVersionId = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, otherSkillId, "1.0.0");
        SkillApiException foreignVersion = assertThrows(SkillApiException.class,
                () -> service.createConversation(slug, otherVersionId));
        assertEquals(SkillApiException.CODE_VERSION_NOT_FOUND, foreignVersion.getCode());
        jdbc.update("DELETE FROM skill WHERE id=?", otherSkillId);
    }

    @Test
    void listConversationsOrdersByUpdatedAtDescAndFiltersVersion() {
        SkillConversation first = service.createConversation(slug, versionId);
        SkillConversation second = service.createConversation(slug, versionId);
        // Pin first's updated_at into the past so ordering is deterministic
        jdbc.update("UPDATE skill_conversation SET updated_at = updated_at - interval '1 hour' WHERE id=?",
                first.getId());

        List<SkillConversation> all = service.listConversations(slug, null);
        assertEquals(2, all.size());
        assertEquals(second.getId(), all.get(0).getId());
        assertEquals(first.getId(), all.get(1).getId());

        List<SkillConversation> filtered = service.listConversations(slug, versionId);
        assertEquals(2, filtered.size());

        Long otherVersion = jdbc.queryForObject(
                "INSERT INTO skill_version(skill_id,version_label) VALUES (?,?) RETURNING id",
                Long.class, skillId, "1.0.1");
        SkillConversation other = service.createConversation(slug, otherVersion);
        assertEquals(3, service.listConversations(slug, null).size());
        List<SkillConversation> onlyOther = service.listConversations(slug, otherVersion);
        assertEquals(1, onlyOther.size());
        assertEquals(other.getId(), onlyOther.get(0).getId());
    }

    @Test
    void listMessagesSupportsExclusiveAfterCursor() {
        SkillConversation conversation = service.createConversation(slug, versionId);
        service.sendMessage(conversation.getId(), "第一条用户输入");

        List<SkillMessage> all = service.listMessages(conversation.getId(), null);
        assertEquals(3, all.size());
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i).getSeq() > all.get(i - 1).getSeq(), "seq must ascend");
        }

        List<SkillMessage> afterWelcome = service.listMessages(conversation.getId(), Integer.valueOf(2));
        assertEquals(1, afterWelcome.size());
        assertEquals("user", afterWelcome.get(0).getRole());

        List<SkillMessage> afterLast = service.listMessages(conversation.getId(),
                all.get(all.size() - 1).getSeq());
        assertTrue(afterLast.isEmpty());

        assertTrue(service.listMessages(null, null).isEmpty());
    }

    @Test
    void sendMessageBackfillsTitleAndRefreshesUpdatedAt() {
        SkillConversation conversation = service.createConversation(slug, versionId);
        SkillConversation before = repository.findConversation(conversation.getId());

        SkillMessage message = service.sendMessage(conversation.getId(), "帮我把这个技能的 description 写清楚");
        assertEquals("user", message.getRole());
        assertEquals("text", message.getMsgType());
        assertEquals(Integer.valueOf(3), message.getSeq());
        assertEquals("帮我把这个技能的 description 写清楚", message.getContent());

        SkillConversation after = repository.findConversation(conversation.getId());
        assertEquals("帮我把这个技能的 description 写清楚", after.getTitle());
        assertTrue(!after.getUpdatedAt().isBefore(before.getUpdatedAt()));

        // Broadcast: welcome x2 + user message
        assertEquals(3, hub.broadcasts.size());
        assertEquals(message.getId(), hub.broadcasts.get(2).getId());

        // Second message keeps the original title, bumps seq
        SkillMessage second = service.sendMessage(conversation.getId(), "换个说法");
        assertEquals(Integer.valueOf(4), second.getSeq());
        assertEquals("帮我把这个技能的 description 写清楚",
                repository.findConversation(conversation.getId()).getTitle());
    }

    @Test
    void sendMessageTruncatesLongTitleToSixtyChars() {
        SkillConversation conversation = service.createConversation(slug, versionId);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longText.append('字');
        }
        service.sendMessage(conversation.getId(), longText.toString());
        String title = repository.findConversation(conversation.getId()).getTitle();
        assertEquals(60, title.length());
    }

    @Test
    void sendMessageRejectsBlankContentAndMissingConversation() {
        SkillApiException nullId = assertThrows(SkillApiException.class,
                () -> service.sendMessage(null, "hi"));
        assertEquals(SkillApiException.CODE_VALIDATION_ERROR, nullId.getCode());

        SkillConversation conversation = service.createConversation(slug, versionId);
        SkillApiException blank = assertThrows(SkillApiException.class,
                () -> service.sendMessage(conversation.getId(), "   "));
        assertEquals(SkillApiException.CODE_VALIDATION_ERROR, blank.getCode());

        SkillApiException missing = assertThrows(SkillApiException.class,
                () -> service.sendMessage(Long.valueOf(999999999L), "hi"));
        assertEquals(SkillConversationServiceImpl.CODE_CONVERSATION_NOT_FOUND, missing.getCode());
        assertNull(repository.findConversation(Long.valueOf(999999999L)));
    }
}
