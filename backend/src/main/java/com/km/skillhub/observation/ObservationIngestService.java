package com.km.skillhub.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Accepts full observation payloads (user text, tool args/results, document bodies).
 * Client and server keep complete sessions and turns, including assistant replies.
 */
@Service
public class ObservationIngestService {
    private final ObservationRepository repository;
    private final ObjectMapper mapper;

    public ObservationIngestService(ObservationRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> ingest(Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("上传内容不能为空");
        Map<String, Object> client = asMap(body.get("client"));
        String clientId = text(client, "clientId", "client_id");
        if (isBlank(clientId)) throw new IllegalArgumentException("clientId 不能为空");
        String hostname = text(client, "hostname");
        String os = text(client, "os");
        String meta = json(client.get("meta"));
        String batchId = text(body, "batchId", "batch_id");
        if (isBlank(batchId)) batchId = java.util.UUID.randomUUID().toString();

        Map<String, String> platform = repository.platformSkills();
        Map<String, String> nameToSlug = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : platform.entrySet()) {
            nameToSlug.put(normalize(entry.getKey()), entry.getKey());
            nameToSlug.put(normalize(entry.getValue()), entry.getKey());
        }

        long clientRowId = repository.upsertClient(clientId.trim(), hostname, os, meta);
        int sessionCount = 0;
        int turnCount = 0;
        int stepCount = 0;
        int skipped = 0;

        for (Object sessionObj : asList(body.get("sessions"))) {
            Map<String, Object> session = asMap(sessionObj);
            String sessionKey = text(session, "sessionId", "session_id", "sessionKey", "session_key");
            if (isBlank(sessionKey)) {
                skipped += 1;
                continue;
            }
            // Contract: discard the whole session if any skill step lacks a SemVer version label.
            // Observation skill identity is version_label only — content digest is ignored.
            if (sessionHasUnversionedSkill(session)) {
                skipped += 1;
                continue;
            }
            String clientName = text(session, "clientName", "client_name");
            if (isBlank(clientName)) clientName = "unknown";
            Instant startedAt = instant(session, "startedAt", "started_at");
            Instant endedAt = instant(session, "endedAt", "ended_at");
            String sessionTitle = text(session, "title", "sessionTitle", "session_title");
            if (!isBlank(sessionTitle)) sessionTitle = sessionTitle.trim();
            if (sessionTitle != null && sessionTitle.length() > 255) sessionTitle = sessionTitle.substring(0, 255);
            long sessionId = repository.upsertSession(clientRowId, sessionKey.trim(), clientName.trim(), startedAt, endedAt, sessionTitle);
            boolean sessionUsed = false;

            for (Object turnObj : asList(session.get("turns"))) {
                Map<String, Object> turn = asMap(turnObj);
                int turnIndex = integer(turn.get("turnIndex"), integer(turn.get("turn_index"), 0));
                List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
                for (Object stepObj : asList(turn.get("steps"))) {
                    steps.add(asMap(stepObj));
                }
                String currentSlug = null;
                List<String> resolvedSlugs = new ArrayList<String>();
                for (Map<String, Object> step : steps) {
                    String resolved = resolveSlug(step, platform, nameToSlug, currentSlug);
                    String type = typeOf(step);
                    if ("skill".equals(type) && resolved != null) {
                        currentSlug = resolved;
                    } else if (resolved != null) {
                        currentSlug = resolved;
                    }
                    resolvedSlugs.add(resolved);
                }
                String userText = text(turn, "userText", "user_text");
                if (isBlank(userText)) {
                    for (int i = 0; i < steps.size(); i++) {
                        if ("user".equals(typeOf(steps.get(i)))) {
                            userText = payloadText(steps.get(i));
                            if (!isBlank(userText)) break;
                        }
                    }
                }
                Instant turnStarted = instant(turn, "startedAt", "started_at");
                if (turnStarted == null && !steps.isEmpty()) turnStarted = instant(steps.get(0), "ts", "timestamp");
                long turnId = repository.upsertTurn(sessionId, turnIndex, turnStarted, ObservationPayloads.sanitizeText(userText == null ? "" : userText));
                currentSlug = null;
                for (int i = 0; i < steps.size(); i++) {
                    Map<String, Object> step = steps.get(i);
                    String type = typeOf(step);
                    String resolved = resolvedSlugs.get(i);
                    if (resolved != null) currentSlug = resolved;
                    String slug;
                    if ("user".equals(type) || "assistant".equals(type)) {
                        slug = resolved;
                    } else if ("skill".equals(type)) {
                        // Unlisted child/local skills stay unattributed; parent rollup already resolved.
                        slug = resolved;
                    } else {
                        slug = resolved != null ? resolved : currentSlug;
                    }
                    String stepId = text(step, "stepId", "step_id");
                    if (isBlank(stepId)) stepId = type + "-" + (i + 1);
                    int seq = integer(step.get("seq"), i + 1);
                    Instant ts = instant(step, "ts", "timestamp");
                    Object rawPayload = step.containsKey("payload") ? step.get("payload") : step.get("data");
                    Map<String, Object> payloadMap = asMap(ObservationPayloads.sanitize(rawPayload));
                    // Never persist content-digest version identity on observation steps.
                    payloadMap.remove("skill_version_digest");
                    payloadMap.remove("skillVersionDigest");
                    payloadMap.remove("version_digest");
                    payloadMap.remove("versionDigest");
                    String payloadJson = json(payloadMap);
                    if (("user".equals(type) || "assistant".equals(type)) && "{}".equals(payloadJson) && !isBlank(payloadText(step))) {
                        Map<String, Object> payload = new LinkedHashMap<String, Object>();
                        payload.put("text", ObservationPayloads.sanitizeText(payloadText(step)));
                        payloadJson = json(payload);
                    } else if ("user".equals(type) && "{}".equals(payloadJson) && !isBlank(userText)) {
                        Map<String, Object> payload = new LinkedHashMap<String, Object>();
                        payload.put("text", ObservationPayloads.sanitizeText(userText));
                        payloadJson = json(payload);
                    }
                    String versionLabel = extractVersionField(step, "skillVersionLabel", "skill_version_label", "versionLabel", "version_label");
                    if (!isBlank(versionLabel)) {
                        versionLabel = versionLabel.trim();
                        if (versionLabel.length() > 64) versionLabel = versionLabel.substring(0, 64);
                    } else {
                        versionLabel = null;
                    }
                    String versionSource = "skill".equals(type) && versionLabel != null ? "observed" : null;
                    // Digest column is not used for observation identity anymore.
                    repository.upsertStep(turnId, stepId, seq, type, ts, slug, payloadJson,
                            null, versionLabel, versionSource);
                    stepCount += 1;
                }
                turnCount += 1;
                sessionUsed = true;
            }
            if (sessionUsed) sessionCount += 1;
        }

        repository.saveBatch(batchId, clientId.trim(), sessionCount, turnCount, stepCount, skipped);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("batchId", batchId);
        result.put("clientId", clientId.trim());
        result.put("sessionCount", Integer.valueOf(sessionCount));
        result.put("turnCount", Integer.valueOf(turnCount));
        result.put("stepCount", Integer.valueOf(stepCount));
        result.put("skippedStepCount", Integer.valueOf(skipped));
        return result;
    }

    private String extractVersionField(Map<String, Object> step, String... keys) {
        String value = text(step, keys);
        if (!isBlank(value)) return value;
        Map<String, Object> payload = asMap(step.get("payload"));
        value = text(payload, keys);
        if (!isBlank(value)) return value;
        Map<String, Object> data = asMap(step.get("data"));
        return text(data, keys);
    }

    /** Whole-session discard when any skill step lacks SemVer version label. */
    private boolean sessionHasUnversionedSkill(Map<String, Object> session) {
        for (Object turnObj : asList(session.get("turns"))) {
            Map<String, Object> turn = asMap(turnObj);
            for (Object stepObj : asList(turn.get("steps"))) {
                Map<String, Object> step = asMap(stepObj);
                if (!"skill".equals(typeOf(step))) continue;
                String label = extractVersionField(step, "skillVersionLabel", "skill_version_label", "versionLabel", "version_label");
                if (!isValidSemVerLabel(label)) return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern SEMVER =
            java.util.regex.Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    static boolean isValidSemVerLabel(String raw) {
        if (raw == null) return false;
        String cleaned = raw.trim();
        if (cleaned.length() > 1 && (cleaned.charAt(0) == 'v' || cleaned.charAt(0) == 'V') && Character.isDigit(cleaned.charAt(1))) {
            cleaned = cleaned.substring(1);
        }
        return SEMVER.matcher(cleaned).matches();
    }

    private String resolveSlug(Map<String, Object> step, Map<String, String> platform, Map<String, String> nameToSlug, String fallback) {
        String slug = text(step, "skillSlug", "skill_slug", "slug");
        if (!isBlank(slug)) {
            if (platform.containsKey(slug)) return slug;
            String mapped = nameToSlug.get(normalize(slug));
            if (mapped != null) return mapped;
            String parent = parentPlatformSlug(slug, platform);
            if (parent != null) return parent;
            // Explicit local skill identity: do not inherit the previous platform skill.
            return null;
        }
        String name = text(step, "skillName", "skill_name", "name");
        Map<String, Object> payload = asMap(step.get("payload"));
        if (isBlank(name)) name = text(payload, "name", "skill", "skillName", "skill_name");
        if (!isBlank(name)) {
            if (platform.containsKey(name)) return name;
            String mapped = nameToSlug.get(normalize(name));
            if (mapped != null) return mapped;
            String parent = parentPlatformSlug(name, platform);
            if (parent != null) return parent;
            // Only ignore non-skill payload names (tool names); keep null for explicit skill_name.
            if (step.containsKey("skillName") || step.containsKey("skill_name") || step.containsKey("skillSlug") || step.containsKey("skill_slug")) {
                return null;
            }
        }
        if (!isBlank(fallback) && platform.containsKey(fallback)) return fallback;
        return null;
    }

    /**
     * Composite children roll up to a platform parent unless the child itself is listed.
     */
    private String parentPlatformSlug(String slugOrName, Map<String, String> platform) {
        String key = normalize(slugOrName);
        if (key.isEmpty()) return null;
        if (key.startsWith("sop-") && platform.containsKey("using-product-development")) {
            return "using-product-development";
        }
        if ("using-superpowers".equals(key) && platform.containsKey("superpowers")) {
            return "superpowers";
        }
        return null;
    }

    private String typeOf(Map<String, Object> step) {
        String type = text(step, "type");
        if (isBlank(type)) return "tool";
        type = type.trim().toLowerCase(Locale.ROOT);
        if ("user".equals(type) || "assistant".equals(type) || "skill".equals(type) || "tool".equals(type) || "document".equals(type)) return type;
        throw new IllegalArgumentException("观测步骤类型无效: " + type);
    }

    private String payloadText(Map<String, Object> step) {
        Map<String, Object> payload = asMap(step.get("payload"));
        String text = text(payload, "text", "content");
        if (!isBlank(text)) return text;
        Object payloadValue = step.get("payload");
        return payloadValue == null ? "" : String.valueOf(payloadValue);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) return (Map<String, Object>) value;
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object value) {
        if (value instanceof List) return (List<Object>) value;
        return Collections.emptyList();
    }

    private String text(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (int i = 0; i < keys.length; i++) {
            Object value = map.get(keys[i]);
            if (value != null && !String.valueOf(value).trim().isEmpty()) return String.valueOf(value);
        }
        return null;
    }

    private Instant instant(Map<String, Object> map, String... keys) {
        String raw = text(map, keys);
        if (isBlank(raw)) return null;
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            try {
                return Instant.parse(raw + "Z");
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String json(Object value) {
        if (value == null) return "{}";
        try {
            String encoded;
            if (value instanceof String) {
                String raw = ((String) value).trim();
                if (raw.startsWith("{") || raw.startsWith("[")) {
                    try {
                        encoded = mapper.writeValueAsString(ObservationPayloads.sanitize(mapper.readValue(raw, Object.class)));
                    } catch (Exception ignored) {
                        encoded = mapper.writeValueAsString(Collections.singletonMap("text", ObservationPayloads.sanitizeText((String) value)));
                    }
                } else {
                    encoded = mapper.writeValueAsString(Collections.singletonMap("text", ObservationPayloads.sanitizeText((String) value)));
                }
            } else {
                encoded = mapper.writeValueAsString(ObservationPayloads.sanitize(value));
            }
            return ObservationPayloads.forJsonb(encoded);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法序列化观测内容");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
