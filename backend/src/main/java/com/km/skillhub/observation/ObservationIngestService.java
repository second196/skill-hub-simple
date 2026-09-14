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
 * Client and server both keep only turns that used a platform skill; nothing is summarized.
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
            String clientName = text(session, "clientName", "client_name");
            if (isBlank(clientName)) clientName = "unknown";
            Instant startedAt = instant(session, "startedAt", "started_at");
            Instant endedAt = instant(session, "endedAt", "ended_at");
            long sessionId = repository.upsertSession(clientRowId, sessionKey.trim(), clientName.trim(), startedAt, endedAt);
            boolean sessionUsed = false;

            for (Object turnObj : asList(session.get("turns"))) {
                Map<String, Object> turn = asMap(turnObj);
                int turnIndex = integer(turn.get("turnIndex"), integer(turn.get("turn_index"), 0));
                List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
                for (Object stepObj : asList(turn.get("steps"))) {
                    steps.add(asMap(stepObj));
                }
                String currentSlug = null;
                boolean hasPlatformSkill = false;
                List<String> resolvedSlugs = new ArrayList<String>();
                for (Map<String, Object> step : steps) {
                    String resolved = resolveSlug(step, platform, nameToSlug, currentSlug);
                    String type = typeOf(step);
                    if ("skill".equals(type) && resolved != null && platform.containsKey(resolved)) {
                        currentSlug = resolved;
                        hasPlatformSkill = true;
                    } else if (resolved != null && platform.containsKey(resolved)) {
                        currentSlug = resolved;
                        hasPlatformSkill = true;
                    }
                    resolvedSlugs.add(resolved);
                }
                if (!hasPlatformSkill) {
                    skipped += steps.size();
                    continue;
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
                long turnId = repository.upsertTurn(sessionId, turnIndex, turnStarted, userText == null ? "" : userText);
                currentSlug = null;
                for (int i = 0; i < steps.size(); i++) {
                    Map<String, Object> step = steps.get(i);
                    String type = typeOf(step);
                    String resolved = resolvedSlugs.get(i);
                    if ("skill".equals(type) && resolved != null && platform.containsKey(resolved)) currentSlug = resolved;
                    else if (resolved != null && platform.containsKey(resolved)) currentSlug = resolved;
                    String slug = resolved != null ? resolved : currentSlug;
                    if (!"user".equals(type) && (slug == null || !platform.containsKey(slug))) {
                        skipped += 1;
                        continue;
                    }
                    String stepId = text(step, "stepId", "step_id");
                    if (isBlank(stepId)) stepId = type + "-" + (i + 1);
                    int seq = integer(step.get("seq"), i + 1);
                    Instant ts = instant(step, "ts", "timestamp");
                    String payloadJson = json(step.containsKey("payload") ? step.get("payload") : step.get("data"));
                    if ("user".equals(type) && "{}".equals(payloadJson) && !isBlank(userText)) {
                        Map<String, Object> payload = new LinkedHashMap<String, Object>();
                        payload.put("text", userText);
                        payloadJson = json(payload);
                    }
                    repository.upsertStep(turnId, stepId, seq, type, ts, slug, payloadJson);
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

    private String resolveSlug(Map<String, Object> step, Map<String, String> platform, Map<String, String> nameToSlug, String fallback) {
        String slug = text(step, "skillSlug", "skill_slug", "slug");
        if (!isBlank(slug) && platform.containsKey(slug)) return slug;
        if (!isBlank(slug) && nameToSlug.containsKey(normalize(slug))) return nameToSlug.get(normalize(slug));
        String name = text(step, "skillName", "skill_name", "name");
        Map<String, Object> payload = asMap(step.get("payload"));
        if (isBlank(name)) name = text(payload, "name", "skill", "skillName", "skill_name");
        if (!isBlank(name) && nameToSlug.containsKey(normalize(name))) return nameToSlug.get(normalize(name));
        if (!isBlank(slug) && nameToSlug.containsKey(normalize(slug))) return nameToSlug.get(normalize(slug));
        return fallback;
    }

    private String typeOf(Map<String, Object> step) {
        String type = text(step, "type");
        if (isBlank(type)) return "tool";
        type = type.trim().toLowerCase(Locale.ROOT);
        if ("user".equals(type) || "skill".equals(type) || "tool".equals(type) || "document".equals(type)) return type;
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
        if (value instanceof String) {
            String raw = ((String) value).trim();
            if (raw.startsWith("{") || raw.startsWith("[")) return raw;
            try {
                return mapper.writeValueAsString(Collections.singletonMap("text", value));
            } catch (Exception e) {
                throw new IllegalArgumentException("无法序列化观测内容");
            }
        }
        try {
            return mapper.writeValueAsString(value);
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
