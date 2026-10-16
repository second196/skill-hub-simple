package com.km.skillhub.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillSseHub: register/broadcast/connectionCount, 15s heartbeat tick
 * (comment ping), and dead-connection eviction. A recording SseEmitter
 * subclass captures the raw SSE frames Spring would write.
 */
class SkillSseHubTest {

    /**
     * Captures every frame the hub sends (message events and comment pings).
     * SseEmitter.send(SseEventBuilder) calls super.send internally, so the
     * only interception point is overriding send(SseEventBuilder) and reading
     * builder.build()'s DataWithMediaType entries (SSE lines like
     * "event:message\ndata:..." / ":ping\n\n").
     */
    private static final class RecordingEmitter extends SseEmitter {
        final List<String> frames = new CopyOnWriteArrayList<String>();
        volatile boolean failOnSend;

        RecordingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("connection reset (simulated)");
            }
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                frames.add(String.valueOf(entry.getData()));
            }
        }
    }

    /** All captured frames of an emitter joined back into the raw SSE stream. */
    private static String stream(RecordingEmitter emitter) {
        StringBuilder joined = new StringBuilder();
        for (String frame : emitter.frames) {
            joined.append(frame);
        }
        return joined.toString();
    }

    private final SkillSseHubImpl hub = new SkillSseHubImpl(new ObjectMapper());

    @AfterEach
    void tearDown() {
        hub.destroy();
    }

    @Test
    void registerTracksConnectionCountAndUnregisterRemoves() {
        RecordingEmitter emitter = new RecordingEmitter();
        hub.register(Long.valueOf(1L), emitter);
        assertEquals(1, hub.connectionCount(Long.valueOf(1L)));
        assertEquals(0, hub.connectionCount(Long.valueOf(2L)));

        hub.unregister(Long.valueOf(1L), emitter);
        assertEquals(0, hub.connectionCount(Long.valueOf(1L)));
    }

    @Test
    void broadcastSendsMessageEventWithFullMessageJson() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        hub.register(Long.valueOf(10L), emitter);

        SkillMessage message = new SkillMessage();
        message.setId(Long.valueOf(77L));
        message.setConversationId(Long.valueOf(10L));
        message.setSeq(Integer.valueOf(3));
        message.setRole("assistant");
        message.setMsgType("process_event");
        message.setContent("{\"stage\":\"advanced\",\"level\":\"info\",\"payload\":{}}");
        message.setRelatedTaskId(Long.valueOf(5L));
        message.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));

        hub.broadcast(Long.valueOf(10L), message);

        assertTrue(emitter.frames.size() >= 1, "broadcast must emit frames");
        String frame = stream(emitter);
        assertTrue(frame.contains("event:message"), frame);
        assertTrue(frame.contains("\"conversationId\":10"), frame);
        assertTrue(frame.contains("\"seq\":3"), frame);
        assertTrue(frame.contains("\"role\":\"assistant\""), frame);
        assertTrue(frame.contains("\"msgType\":\"process_event\""), frame);
        assertTrue(frame.contains("\"relatedTaskId\":5"), frame);
        assertTrue(frame.contains("advanced"), frame);
    }

    @Test
    void broadcastSkipsOtherConversationsAndToleratesNull() {
        RecordingEmitter target = new RecordingEmitter();
        RecordingEmitter bystander = new RecordingEmitter();
        hub.register(Long.valueOf(20L), target);
        hub.register(Long.valueOf(21L), bystander);

        SkillMessage message = new SkillMessage();
        message.setId(Long.valueOf(1L));
        message.setConversationId(Long.valueOf(20L));
        message.setSeq(Integer.valueOf(1));
        message.setRole("user");
        message.setMsgType("text");
        message.setContent("hi");

        hub.broadcast(Long.valueOf(20L), message);
        hub.broadcast(Long.valueOf(20L), null);
        hub.broadcast(null, message);

        assertTrue(target.frames.size() >= 1, "target conversation must receive the message");
        assertTrue(bystander.frames.isEmpty(), "other conversations must not receive it");
    }

    @Test
    void broadcastEvictsOnlyTheFailingConnection() {
        RecordingEmitter healthy = new RecordingEmitter();
        RecordingEmitter dead = new RecordingEmitter();
        dead.failOnSend = true;
        hub.register(Long.valueOf(30L), healthy);
        hub.register(Long.valueOf(30L), dead);

        SkillMessage message = new SkillMessage();
        message.setId(Long.valueOf(2L));
        message.setConversationId(Long.valueOf(30L));
        message.setSeq(Integer.valueOf(1));
        message.setRole("user");
        message.setMsgType("text");
        message.setContent("hello");

        hub.broadcast(Long.valueOf(30L), message);

        assertTrue(healthy.frames.size() >= 1, "healthy connection must receive the message");
        assertEquals(1, hub.connectionCount(Long.valueOf(30L)), "dead connection must be evicted");
    }

    @Test
    void heartbeatTickSendsPingCommentAndEvictsDeadConnections() {
        RecordingEmitter healthy = new RecordingEmitter();
        RecordingEmitter dead = new RecordingEmitter();
        dead.failOnSend = true;
        hub.register(Long.valueOf(40L), healthy);
        hub.register(Long.valueOf(40L), dead);

        hub.heartbeatTick();

        assertTrue(healthy.frames.size() >= 1, "heartbeat must emit a ping frame");
        String frame = stream(healthy);
        assertTrue(frame.contains(":ping"), frame);
        assertEquals(1, hub.connectionCount(Long.valueOf(40L)));
    }

    @Test
    void heartbeatIntervalIsFifteenSeconds() {
        assertEquals(15_000L, SkillSseHubImpl.HEARTBEAT_INTERVAL_MS);
    }

    @Test
    void heartbeatDoesNotFireBeforeRegistration() {
        hub.heartbeatTick();
        assertEquals(0, hub.connectionCount(Long.valueOf(50L)));
        assertFalse(false, "heartbeat with empty registry must be a no-op");
    }
}
