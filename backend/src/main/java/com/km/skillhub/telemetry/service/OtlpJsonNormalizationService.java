package com.km.skillhub.telemetry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.telemetry.domain.TelemetryErrorCode;
import com.km.skillhub.telemetry.model.dto.OtlpLogRequest;
import com.km.skillhub.telemetry.model.dto.OtlpTraceRequest;
import com.km.skillhub.telemetry.model.entity.RuntimeEventEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class OtlpJsonNormalizationService {
    private static final Pattern VERSION_DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final String DEFAULT_SCHEMA_VERSION = "1.0";

    private final ServerPrivacyPolicy privacyPolicy;
    private final ObjectMapper objectMapper;

    public OtlpJsonNormalizationService(ServerPrivacyPolicy privacyPolicy, ObjectMapper objectMapper) {
        this.privacyPolicy = privacyPolicy;
        this.objectMapper = objectMapper;
    }

    public NormalizationResult normalizeTraces(OtlpTraceRequest request) {
        if (request == null || request.getResourceSpans() == null || request.getResourceSpans().isEmpty()) {
            throw failure(TelemetryErrorCode.INVALID_OTLP_STRUCTURE);
        }
        Accumulator result = new Accumulator();
        for (OtlpTraceRequest.ResourceSpans resourceSpans : request.getResourceSpans()) {
            ResourceContext context = resourceContext(resourceSpans == null ? null : resourceSpans.getResource());
            result.acceptContext(context);
            if (resourceSpans == null || resourceSpans.getScopeSpans() == null) continue;
            for (OtlpTraceRequest.ScopeSpans scopeSpans : resourceSpans.getScopeSpans()) {
                if (scopeSpans == null || scopeSpans.getSpans() == null) continue;
                for (OtlpTraceRequest.Span span : scopeSpans.getSpans()) {
                    result.received++;
                    try {
                        result.events.add(traceEvent(span, context, result.received));
                    } catch (EventRejectedException exception) {
                        result.reject(exception.code);
                    }
                }
            }
        }
        return result.complete();
    }

    public NormalizationResult normalizeLogs(OtlpLogRequest request) {
        if (request == null || request.getResourceLogs() == null || request.getResourceLogs().isEmpty()) {
            throw failure(TelemetryErrorCode.INVALID_OTLP_STRUCTURE);
        }
        Accumulator result = new Accumulator();
        for (OtlpLogRequest.ResourceLogs resourceLogs : request.getResourceLogs()) {
            ResourceContext context = resourceContext(resourceLogs == null ? null : resourceLogs.getResource());
            result.acceptContext(context);
            if (resourceLogs == null || resourceLogs.getScopeLogs() == null) continue;
            for (OtlpLogRequest.ScopeLogs scopeLogs : resourceLogs.getScopeLogs()) {
                if (scopeLogs == null || scopeLogs.getLogRecords() == null) continue;
                for (OtlpLogRequest.LogRecord record : scopeLogs.getLogRecords()) {
                    result.received++;
                    try {
                        result.events.add(logEvent(record, context, result.received));
                    } catch (EventRejectedException exception) {
                        result.reject(exception.code);
                    }
                }
            }
        }
        return result.complete();
    }

    private RuntimeEventEntity traceEvent(OtlpTraceRequest.Span span, ResourceContext context, long sequence) {
        if (span == null || blank(span.getTraceId()) || blank(span.getSpanId())
                || blank(span.getStartTimeUnixNano())) {
            throw rejected(TelemetryErrorCode.EVENT_REQUIRED_FIELD_MISSING);
        }
        Map<String, Object> attributes = attributes(span.getAttributes());
        OffsetDateTime occurredAt = unixNano(span.getStartTimeUnixNano());
        RuntimeEventEntity event = baseEvent(context, attributes, sequence, occurredAt,
                required(span.getTraceId(), 128), required(span.getSpanId(), 128));
        event.setParentSpanId(optional(span.getParentSpanId(), 128));
        event.setEventType(eventType(attributes, "SPAN_COMPLETED"));
        event.setStatus(traceStatus(span));
        event.setDurationMs(durationMillis(span.getStartTimeUnixNano(), span.getEndTimeUnixNano()));
        applyDomainAttributes(event, attributes);
        applyPrivacy(event, context.attributes, attributes);
        return event;
    }

    private RuntimeEventEntity logEvent(OtlpLogRequest.LogRecord record, ResourceContext context, long sequence) {
        String timestamp = record == null ? null : first(record.getTimeUnixNano(), record.getObservedTimeUnixNano());
        if (record == null || blank(record.getTraceId()) || blank(record.getSpanId()) || blank(timestamp)) {
            throw rejected(TelemetryErrorCode.EVENT_REQUIRED_FIELD_MISSING);
        }
        Map<String, Object> attributes = attributes(record.getAttributes());
        RuntimeEventEntity event = baseEvent(context, attributes, sequence, unixNano(timestamp),
                required(record.getTraceId(), 128), required(record.getSpanId(), 128));
        event.setEventType(eventType(attributes, "LOG_RECORD"));
        event.setStatus(logStatus(record));
        applyDomainAttributes(event, attributes);
        applyPrivacy(event, context.attributes, attributes);
        return event;
    }

    private RuntimeEventEntity baseEvent(ResourceContext context, Map<String, Object> attributes,
                                         long sequence, OffsetDateTime occurredAt,
                                         String traceId, String spanId) {
        RuntimeEventEntity event = new RuntimeEventEntity();
        String eventId = string(attributes, "event.id");
        event.setEventId(optional(blank(eventId) ? traceId + ":" + spanId : eventId, 128));
        if (event.getEventId() == null) throw rejected(TelemetryErrorCode.EVENT_REQUIRED_FIELD_MISSING);
        event.setSchemaVersion(context.schemaVersion);
        event.setScopeId(context.scopeId);
        event.setRuntimeKey(context.runtimeKey);
        event.setRuntimeVersion(context.runtimeVersion);
        event.setTrackerVersion(optional(string(attributes, "tracker.version"), 64));
        event.setSessionId(required(firstString(attributes, "session.id", "witty.session.id", "service.instance.id",
                traceId), 128));
        event.setTraceId(traceId);
        event.setSpanId(spanId);
        Long explicitSequence = longValue(attributes, "event.sequence");
        event.setSequence(explicitSequence == null ? sequence : explicitSequence);
        if (event.getSequence() < 0) throw rejected(TelemetryErrorCode.EVENT_FIELD_INVALID);
        event.setOccurredAt(occurredAt);
        return event;
    }

    private void applyDomainAttributes(RuntimeEventEntity event, Map<String, Object> attributes) {
        event.setAgentId(optional(firstString(attributes, "agent.id", "witty.agent.id"), 128));
        event.setParentAgentId(optional(firstString(attributes, "parent.agent.id", "witty.parent.agent.id"), 128));
        event.setModelName(optional(firstString(attributes, "llm.model_name", "gen_ai.response.model",
                "gen_ai.request.model"), 128));
        event.setToolName(optional(firstString(attributes, "tool.name", "witty.tool.name"), 128));
        event.setMcpServer(optional(string(attributes, "mcp.server.name"), 128));
        event.setSkillName(optional(firstString(attributes, "skill.name", "witty.skill.name"), 128));
        event.setInvocationId(optional(string(attributes, "invocation.id"), 128));
        if (event.getInvocationId() == null && event.getSkillName() != null) event.setInvocationId(event.getEventId());
        event.setTriggerType(optional(firstString(attributes, "skill.trigger_mode", "witty.skill.trigger_type"), 64));
        event.setInputTokens(nonNegativeLong(attributes, "llm.token_count.prompt", "gen_ai.usage.input_tokens",
                "gen_ai.usage.prompt_tokens"));
        event.setOutputTokens(nonNegativeLong(attributes, "llm.token_count.completion", "gen_ai.usage.output_tokens",
                "gen_ai.usage.completion_tokens"));
        event.setCost(nonNegativeDecimal(attributes, "cost"));
        if (event.getDurationMs() == null) event.setDurationMs(nonNegativeLong(attributes, "duration.ms"));

        String digest = firstString(attributes, "skill.version", "witty.skill.version");
        if (digest != null) digest = digest.toLowerCase(Locale.ROOT);
        if (digest != null && VERSION_DIGEST.matcher(digest).matches()) {
            event.setVersionDigest(digest);
            event.setVersionUnknown(false);
        } else {
            event.setVersionUnknown(true);
            event.setVersionUnknownReason(digest == null
                    ? "运行时未提供明确技能版本摘要" : "技能版本摘要格式无效");
        }
    }

    private void applyPrivacy(RuntimeEventEntity event, Map<String, Object> resourceAttributes,
                              Map<String, Object> eventAttributes) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>();
        merged.putAll(resourceAttributes);
        merged.putAll(eventAttributes);
        ServerPrivacyPolicy.Result privacy = privacyPolicy.apply(merged);
        Map<String, Object> sanitized = new LinkedHashMap<String, Object>(privacy.getAttributes());
        LinkedHashSet<String> missing = strings(sanitized.remove("missing.fields"));
        LinkedHashSet<String> actions = strings(sanitized.remove("privacy.actions"));
        if ("unknown".equals(event.getRuntimeVersion())) missing.add("runtimeVersion");
        if (event.getVersionUnknown()) missing.add("versionDigest");
        actions.addAll(privacy.getActions());
        event.setAttributes(json(sanitized));
        event.setMissingFields(json(missing));
        event.setPrivacyActions(json(actions));
    }

    private LinkedHashSet<String> strings(Object value) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        if (!(value instanceof List<?>)) return result;
        for (Object item : (List<?>) value) {
            if (item instanceof String && !blank((String) item) && ((String) item).length() <= 128) {
                result.add((String) item);
            }
        }
        return result;
    }

    private ResourceContext resourceContext(OtlpTraceRequest.Resource resource) {
        Map<String, Object> attributes = attributes(resource == null ? null : resource.getAttributes());
        Long scopeId = longValue(attributes, "skillhub.scope.id");
        if (scopeId == null || scopeId <= 0) throw failure(TelemetryErrorCode.SCOPE_REQUIRED);
        String runtimeKey = string(attributes, "service.name");
        if (blank(runtimeKey)) throw failure(TelemetryErrorCode.RUNTIME_REQUIRED);
        String schemaVersion = string(attributes, "skillhub.schema.version");
        if (blank(schemaVersion)) schemaVersion = DEFAULT_SCHEMA_VERSION;
        if (!DEFAULT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw failure(TelemetryErrorCode.UNSUPPORTED_SCHEMA_VERSION);
        }
        String runtimeVersion = string(attributes, "service.version");
        if (blank(runtimeVersion)) runtimeVersion = "unknown";
        try {
            return new ResourceContext(scopeId, required(runtimeKey, 64), required(runtimeVersion, 64),
                    schemaVersion, attributes);
        } catch (EventRejectedException exception) {
            throw failure(exception.code);
        }
    }

    private Map<String, Object> attributes(List<OtlpTraceRequest.KeyValue> values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (values == null) return result;
        for (OtlpTraceRequest.KeyValue value : values) {
            if (value == null || blank(value.getKey()) || value.getValue() == null) continue;
            Object decoded = anyValue(value.getValue());
            if (decoded != null) result.put(value.getKey(), decoded);
        }
        return result;
    }

    private Object anyValue(JsonNode node) {
        if (node.has("stringValue")) return node.get("stringValue").asText();
        if (node.has("intValue")) return node.get("intValue").isNumber()
                ? node.get("intValue").longValue() : parseLongOrString(node.get("intValue").asText());
        if (node.has("doubleValue")) return node.get("doubleValue").doubleValue();
        if (node.has("boolValue")) return node.get("boolValue").booleanValue();
        if (node.has("arrayValue") && node.get("arrayValue").has("values")) {
            List<Object> result = new ArrayList<Object>();
            for (JsonNode item : node.get("arrayValue").get("values")) {
                Object decoded = anyValue(item);
                if (decoded != null) result.add(decoded);
            }
            return result;
        }
        if (node.has("kvlistValue") && node.get("kvlistValue").has("values")) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (JsonNode item : node.get("kvlistValue").get("values")) {
                if (!item.hasNonNull("key") || !item.has("value")) continue;
                Object decoded = anyValue(item.get("value"));
                if (decoded != null) result.put(item.get("key").asText(), decoded);
            }
            return result;
        }
        return null;
    }

    private Object parseLongOrString(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return value;
        }
    }

    private OffsetDateTime unixNano(String value) {
        try {
            BigInteger nanos = new BigInteger(value);
            if (nanos.signum() < 0) throw new NumberFormatException("negative");
            BigInteger[] parts = nanos.divideAndRemainder(BigInteger.valueOf(1000000000L));
            Instant instant = Instant.ofEpochSecond(parts[0].longValueExact(), parts[1].longValue());
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            throw rejected(TelemetryErrorCode.EVENT_TIME_INVALID);
        }
    }

    private Long durationMillis(String start, String end) {
        if (blank(end)) return null;
        try {
            BigInteger duration = new BigInteger(end).subtract(new BigInteger(start));
            if (duration.signum() < 0) throw new NumberFormatException("negative");
            return duration.divide(BigInteger.valueOf(1000000L)).longValueExact();
        } catch (RuntimeException exception) {
            throw rejected(TelemetryErrorCode.EVENT_TIME_INVALID);
        }
    }

    private String eventType(Map<String, Object> attributes, String fallback) {
        String explicit = string(attributes, "event.type");
        if (!blank(explicit)) return required(explicit, 64);
        if (!blank(firstString(attributes, "skill.name", "witty.skill.name"))) return "SKILL_INVOCATION";
        if (!blank(firstString(attributes, "tool.name", "witty.tool.name"))) return "TOOL_INVOCATION";
        if (!blank(firstString(attributes, "llm.model_name", "gen_ai.request.model"))) return "MODEL_INVOCATION";
        return fallback;
    }

    private String traceStatus(OtlpTraceRequest.Span span) {
        Integer code = span.getStatus() == null ? null : span.getStatus().getCode();
        if (code != null && code == 2) return "FAILED";
        if (code != null && code == 1) return "SUCCEEDED";
        return "UNKNOWN";
    }

    private String logStatus(OtlpLogRequest.LogRecord record) {
        if (record.getSeverityNumber() != null && record.getSeverityNumber() >= 17) return "FAILED";
        String severity = record.getSeverityText();
        return severity != null && severity.toUpperCase(Locale.ROOT).contains("ERROR") ? "FAILED" : "SUCCEEDED";
    }

    private Long nonNegativeLong(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Long value = longValue(values, key);
            if (value != null) {
                if (value < 0) throw rejected(TelemetryErrorCode.EVENT_FIELD_INVALID);
                return value;
            }
        }
        return null;
    }

    private BigDecimal nonNegativeDecimal(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(value));
            if (decimal.signum() < 0) throw new NumberFormatException("negative");
            return decimal;
        } catch (NumberFormatException exception) {
            throw rejected(TelemetryErrorCode.EVENT_FIELD_INVALID);
        }
    }

    private Long longValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String firstString(Map<String, Object> values, String... keysOrFallback) {
        for (String item : keysOrFallback) {
            if (values.containsKey(item)) {
                String value = string(values, item);
                if (!blank(value)) return value;
            }
        }
        if (keysOrFallback.length > 0) {
            String fallback = keysOrFallback[keysOrFallback.length - 1];
            if (!fallback.contains(".")) return fallback;
        }
        return null;
    }

    private String first(String first, String second) {
        return blank(first) ? second : first;
    }

    private String required(String value, int maxLength) {
        String result = optional(value, maxLength);
        if (result == null) throw rejected(TelemetryErrorCode.EVENT_REQUIRED_FIELD_MISSING);
        return result;
    }

    private String optional(String value, int maxLength) {
        if (blank(value)) return null;
        String result = value.trim();
        if (result.length() > maxLength) throw rejected(TelemetryErrorCode.EVENT_FIELD_INVALID);
        return result;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化遥测事件属性", exception);
        }
    }

    private TelemetryIngestService.TelemetryIngestException failure(TelemetryErrorCode code) {
        return new TelemetryIngestService.TelemetryIngestException(code);
    }

    private EventRejectedException rejected(TelemetryErrorCode code) {
        return new EventRejectedException(code);
    }

    private static class ResourceContext {
        private final Long scopeId;
        private final String runtimeKey;
        private final String runtimeVersion;
        private final String schemaVersion;
        private final Map<String, Object> attributes;
        ResourceContext(Long scopeId, String runtimeKey, String runtimeVersion, String schemaVersion,
                        Map<String, Object> attributes) {
            this.scopeId = scopeId;
            this.runtimeKey = runtimeKey;
            this.runtimeVersion = runtimeVersion;
            this.schemaVersion = schemaVersion;
            this.attributes = attributes;
        }
    }

    private static class EventRejectedException extends RuntimeException {
        private final TelemetryErrorCode code;
        EventRejectedException(TelemetryErrorCode code) { this.code = code; }
    }

    private static class Accumulator {
        private Long scopeId;
        private String runtimeKey;
        private int received;
        private final List<RuntimeEventEntity> events = new ArrayList<RuntimeEventEntity>();
        private final Map<String, Integer> rejectionReasons = new LinkedHashMap<String, Integer>();

        private void acceptContext(ResourceContext context) {
            if (scopeId != null && !scopeId.equals(context.scopeId)) {
                throw new TelemetryIngestService.TelemetryIngestException(TelemetryErrorCode.SCOPE_MISMATCH);
            }
            if (runtimeKey != null && !runtimeKey.equals(context.runtimeKey)) {
                throw new TelemetryIngestService.TelemetryIngestException(TelemetryErrorCode.RUNTIME_MISMATCH);
            }
            scopeId = context.scopeId;
            runtimeKey = context.runtimeKey;
        }

        private void reject(TelemetryErrorCode code) {
            Integer count = rejectionReasons.get(code.name());
            rejectionReasons.put(code.name(), count == null ? 1 : count + 1);
        }

        private NormalizationResult complete() {
            if (received == 0 || scopeId == null || runtimeKey == null) {
                throw new TelemetryIngestService.TelemetryIngestException(TelemetryErrorCode.INVALID_OTLP_STRUCTURE);
            }
            return new NormalizationResult(scopeId, runtimeKey, received, events, rejectionReasons);
        }
    }

    public static class NormalizationResult {
        private final Long scopeId;
        private final String runtimeKey;
        private final int received;
        private final List<RuntimeEventEntity> events;
        private final Map<String, Integer> rejectionReasons;
        NormalizationResult(Long scopeId, String runtimeKey, int received, List<RuntimeEventEntity> events,
                            Map<String, Integer> rejectionReasons) {
            this.scopeId = scopeId;
            this.runtimeKey = runtimeKey;
            this.received = received;
            this.events = Collections.unmodifiableList(new ArrayList<RuntimeEventEntity>(events));
            this.rejectionReasons = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Integer>(rejectionReasons));
        }
        public Long getScopeId() { return scopeId; }
        public String getRuntimeKey() { return runtimeKey; }
        public int getReceived() { return received; }
        public List<RuntimeEventEntity> getEvents() { return events; }
        public Map<String, Integer> getRejectionReasons() { return rejectionReasons; }
        public int getRejected() { return received - events.size(); }
    }
}
