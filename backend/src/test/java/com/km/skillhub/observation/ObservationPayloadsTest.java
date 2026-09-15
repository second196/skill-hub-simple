package com.km.skillhub.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationPayloadsTest {
    @Test
    void replacesZipPayloadBeforeJsonb() {
        String zip = "PK\u0003\u0004\u0014\u0000actual: |-\n        binary";
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", "Read");
        payload.put("result", zip);

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) ObservationPayloads.sanitize(payload);
        String result = String.valueOf(sanitized.get("result"));

        assertTrue(result.contains("ZIP"));
        assertFalse(result.contains("\u0000"));
        assertFalse(ObservationPayloads.forJsonb("{\"result\":\"" + result + "\"}").contains("\\u0000"));
    }

    @Test
    void stripsNullEscapesFromJsonbText() {
        String json = "{\"actual\":\"PK\\u0003\\u0004\\u0000\"}";
        String cleaned = ObservationPayloads.forJsonb(json);
        assertEquals("{\"actual\":\"PK\\u0003\\u0004\"}", cleaned);
        assertFalse(cleaned.contains("\\u0000"));
        assertFalse(cleaned.contains("\u0000"));
    }

    @Test
    void keepsLiteralUnicodeEscapeInSourceText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("text", "on src/slugify.ts\r\n 9022: const rControl = /[\\u0000-\\u001f]/");
        String json = mapper.writeValueAsString(payload);
        String cleaned = ObservationPayloads.forJsonb(json);
        assertEquals(json, cleaned);
        assertTrue(cleaned.contains("[\\\\u0000-\\\\u001f]"));
        assertFalse(cleaned.contains("\\-"));
    }

    @Test
    void keepsOrdinaryText() {
        assertEquals("hello\nworld", ObservationPayloads.sanitizeText("hello\nworld"));
    }

    @Test
    void replacesZipPayloadEvenWhenHeaderIsNotAtStart() {
        String zip = "actual: |-\n        " + "PK\u0003\u0004\u0014\u0000binary";
        String sanitized = ObservationPayloads.sanitizeText(zip);
        assertTrue(sanitized.contains("ZIP"));
        assertFalse(sanitized.contains("\u0000"));
        assertFalse(ObservationPayloads.forJsonb("{\"actual\":\"" + sanitized + "\"}").contains("\\u0000"));
    }
}
