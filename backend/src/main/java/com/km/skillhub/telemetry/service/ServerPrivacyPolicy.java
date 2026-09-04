package com.km.skillhub.telemetry.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ServerPrivacyPolicy {
    private static final Set<String> ALLOWED_KEYS = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "service.name", "service.version", "telemetry.sdk.name", "telemetry.sdk.language",
            "telemetry.sdk.version", "skillhub.schema.version", "session.id", "witty.session.id",
            "agent.id", "witty.agent.id", "parent.agent.id", "witty.parent.agent.id",
            "llm.model_name", "gen_ai.request.model", "gen_ai.response.model",
            "llm.token_count.prompt", "llm.token_count.completion", "gen_ai.usage.input_tokens",
            "gen_ai.usage.prompt_tokens", "gen_ai.usage.output_tokens", "gen_ai.usage.completion_tokens",
            "tool.name", "witty.tool.name", "mcp.server.name", "skill.name", "witty.skill.name",
            "skill.version", "witty.skill.version", "skill.trigger_mode", "witty.skill.trigger_type",
            "event.id", "event.type", "event.sequence", "invocation.id", "tracker.version",
            "duration.ms", "cost", "status", "witty.log.category", "witty.log.level",
            "witty.trace.id", "trace.id", "summary", "missing.fields", "privacy.actions"
    )));
    private static final Pattern CREDENTIAL_VALUE = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|token|secret|password)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)(?:[a-z]:\\\\|\\\\\\\\)[^\\r\\n\\t ]+");
    private static final Pattern UNIX_PATH = Pattern.compile("/(?:Users|home)/[^\\r\\n\\t ]+");

    private final int maxTextLength;

    public ServerPrivacyPolicy(@Value("${skillhub.telemetry.privacy.max-text-length:2000}") int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }

    public Result apply(Map<String, Object> attributes) {
        Map<String, Object> sanitized = new LinkedHashMap<String, Object>();
        List<String> actions = new ArrayList<String>();
        if (attributes == null) return new Result(sanitized, actions);
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String key = entry.getKey();
            if (key == null || !ALLOWED_KEYS.contains(key)) {
                if (isProtectedKey(key)) actions.add("DROPPED_PROTECTED_FIELD:" + safeKey(key));
                continue;
            }
            Object value = sanitizeValue(entry.getValue(), actions);
            if (value != null) sanitized.put(key, value);
        }
        return new Result(sanitized, actions);
    }

    private Object sanitizeValue(Object value, List<String> actions) {
        if (value instanceof String) return sanitizeText((String) value, actions);
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                Object sanitized = sanitizeValue(item, actions);
                if (sanitized != null) result.add(sanitized);
            }
            return result;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                if (!(item.getKey() instanceof String) || isProtectedKey((String) item.getKey())) continue;
                Object sanitized = sanitizeValue(item.getValue(), actions);
                if (sanitized != null) result.put((String) item.getKey(), sanitized);
            }
            return result;
        }
        return null;
    }

    private String sanitizeText(String value, List<String> actions) {
        String result = CREDENTIAL_VALUE.matcher(value).replaceAll("$1=[已脱敏]");
        if (!result.equals(value)) actions.add("REDACTED_CREDENTIAL");
        String withoutWindowsPath = WINDOWS_PATH.matcher(result).replaceAll("[本地路径]");
        String withoutLocalPath = UNIX_PATH.matcher(withoutWindowsPath).replaceAll("[本地路径]");
        if (!withoutLocalPath.equals(result)) actions.add("REDACTED_LOCAL_PATH");
        if (withoutLocalPath.length() > maxTextLength) {
            actions.add("TRUNCATED_TEXT");
            return withoutLocalPath.substring(0, maxTextLength);
        }
        return withoutLocalPath;
    }

    private boolean isProtectedKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("prompt") || lower.contains("transcript") || lower.contains("code")
                || lower.contains("input") || lower.contains("output") || lower.contains("body")
                || lower.contains("authorization") || lower.contains("cookie") || lower.contains("password")
                || lower.contains("secret") || lower.contains("token") || lower.contains("terminal")
                || lower.contains("fileedit");
    }

    private String safeKey(String key) {
        return key == null ? "unknown" : key.substring(0, Math.min(64, key.length()));
    }

    public static class Result {
        private final Map<String, Object> attributes;
        private final List<String> actions;
        Result(Map<String, Object> attributes, List<String> actions) {
            this.attributes = attributes;
            this.actions = actions;
        }
        public Map<String, Object> getAttributes() { return attributes; }
        public List<String> getActions() { return actions; }
    }
}
