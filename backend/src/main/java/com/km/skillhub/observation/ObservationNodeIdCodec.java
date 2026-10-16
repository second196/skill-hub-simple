package com.km.skillhub.observation;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Dashboard node id codec: "step|sessionRowId|turnIndex|stepId" and
 * "subagent|childSessionRowId", both URL-safe base64 encoded.
 *
 * <p>Ported from kmplm ObservationNodeIdCodec behaviour; ids must round-trip so the
 * node detail endpoint can resolve a graph node back to one observation_step row.</p>
 */
public final class ObservationNodeIdCodec {

    private ObservationNodeIdCodec() {
    }

    public static String encodeStepNode(long sessionRowId, int turnIndex, String stepId) {
        return encode("step|" + sessionRowId + "|" + turnIndex + "|" + safe(stepId));
    }

    public static String encodeSubagentNode(long childSessionRowId) {
        return encode("subagent|" + childSessionRowId);
    }

    public static Decoded decode(String nodeId) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(nodeId), StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("节点标识无效");
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length == 4 && "step".equals(parts[0])) {
            return new Decoded("step", longOf(parts[1]), Integer.valueOf(intOf(parts[2])), parts[3]);
        }
        if (parts.length == 2 && "subagent".equals(parts[0])) {
            return new Decoded("subagent", longOf(parts[1]), null, null);
        }
        throw new IllegalArgumentException("节点标识无效");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("|", "%7C");
    }

    private static long longOf(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("节点标识无效");
        }
    }

    private static int intOf(String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("节点标识无效");
        }
    }

    public static final class Decoded {
        private final String kind;
        private final long sessionRowId;
        private final Integer turnIndex;
        private final String stepId;

        private Decoded(String kind, long sessionRowId, Integer turnIndex, String stepId) {
            this.kind = kind;
            this.sessionRowId = sessionRowId;
            this.turnIndex = turnIndex;
            this.stepId = stepId;
        }

        public String getKind() {
            return kind;
        }

        public long getSessionRowId() {
            return sessionRowId;
        }

        public Integer getTurnIndex() {
            return turnIndex;
        }

        public String getStepId() {
            return stepId;
        }
    }
}
