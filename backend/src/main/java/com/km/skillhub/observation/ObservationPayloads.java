package com.km.skillhub.observation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL jsonb rejects NUL bytes. Observation traces may embed ZIP or other binary content
 * as JSON strings; keep full text, but replace those blobs before they are stored.
 */
final class ObservationPayloads {
    private static final int SAMPLE_LIMIT = 4096;
    private static final int MIN_CONTROL_LENGTH = 16;

    private ObservationPayloads() {
    }

    static Object sanitize(Object value) {
        if (value == null) return null;
        if (value instanceof String) return sanitizeText((String) value);
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            return omitted(bytes.length, kindOf(bytes));
        }
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() == null) continue;
                copy.put(String.valueOf(entry.getKey()), sanitize(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<?> source = (List<?>) value;
            List<Object> copy = new ArrayList<Object>(source.size());
            for (int i = 0; i < source.size(); i++) {
                copy.add(sanitize(source.get(i)));
            }
            return copy;
        }
        if (value instanceof Object[]) {
            Object[] source = (Object[]) value;
            List<Object> copy = new ArrayList<Object>(source.length);
            for (int i = 0; i < source.length; i++) {
                copy.add(sanitize(source[i]));
            }
            return copy;
        }
        return value;
    }

    static String sanitizeText(String value) {
        if (value == null || value.isEmpty()) return value;
        if (kindOf(value) != null || looksMostlyBinary(value)) {
            return omitted(value.getBytes(StandardCharsets.UTF_8).length, kindOf(value));
        }
        if (value.indexOf('\0') >= 0) return value.replace("\0", "");
        return value;
    }

    static String forJsonb(String json) {
        if (json == null) return "{}";
        String trimmed = json.trim();
        if (trimmed.isEmpty()) return "{}";
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); ) {
            char c = trimmed.charAt(i);
            if (c == '\u0000') {
                i += 1;
                continue;
            }
            if (isUnescapedNulEscape(trimmed, i)) {
                i += 6;
                continue;
            }
            out.append(c);
            i += 1;
        }
        return out.toString();
    }

    static boolean looksBinary(String value) {
        if (value == null || value.isEmpty()) return false;
        return kindOf(value) != null || looksMostlyBinary(value) || value.indexOf('\0') >= 0;
    }

    /**
     * PostgreSQL rejects the JSON unicode escape {@code \u0000}. Literal source text such as
     * {@code /[\u0000-\u001f]/} is serialized as {@code \\u0000} and must be kept; stripping
     * every {@code \u0000} substring turns that into the illegal escape {@code \-}.
     */
    private static boolean isUnescapedNulEscape(String json, int index) {
        if (index + 6 > json.length()) return false;
        if (json.charAt(index) != '\\') return false;
        char marker = json.charAt(index + 1);
        if (marker != 'u' && marker != 'U') return false;
        for (int offset = 2; offset < 6; offset++) {
            if (json.charAt(index + offset) != '0') return false;
        }
        int slashes = 0;
        for (int cursor = index; cursor >= 0 && json.charAt(cursor) == '\\'; cursor--) {
            slashes += 1;
        }
        return slashes % 2 == 1;
    }

    private static boolean looksMostlyBinary(String value) {
        int n = Math.min(value.length(), SAMPLE_LIMIT);
        int control = 0;
        for (int i = 0; i < n; i++) {
            char c = value.charAt(i);
            if (c < 32 && c != '\t' && c != '\n' && c != '\r') control++;
        }
        return n >= MIN_CONTROL_LENGTH && control * 20 > n;
    }

    private static String kindOf(String value) {
        if (value.indexOf("PK\u0003\u0004") >= 0) return "ZIP";
        if (value.indexOf("%PDF-") >= 0) return "PDF";
        if (value.length() >= 2 && value.charAt(0) == 0x1f && value.charAt(1) == 0x8b) return "GZIP";
        if (value.startsWith("\u0089PNG")) return "PNG";
        return null;
    }

    private static String kindOf(byte[] bytes) {
        if (bytes == null || bytes.length < 2) return null;
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4) return "ZIP";
        if (bytes[0] == 0x1f && bytes[1] == (byte) 0x8b) return "GZIP";
        return null;
    }

    private static String omitted(int bytes, String kind) {
        if (kind == null || kind.isEmpty()) {
            return "[\u5df2\u7701\u7565\u4e8c\u8fdb\u5236\u5185\u5bb9\uff0c" + bytes + " \u5b57\u8282]";
        }
        return "[\u5df2\u7701\u7565\u4e8c\u8fdb\u5236\u5185\u5bb9\uff08" + kind + "\uff09\uff0c" + bytes + " \u5b57\u8282]";
    }
}
