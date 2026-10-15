package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisModelClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void staysDisabledWhenNoEndpointIsConfigured() {
        AnalysisModelClient client = new AnalysisModelClient(
                mapper, true, "", "", "model", "embedding", 1000, 100, 1000);
        assertFalse(client.isEnabled());
        assertFalse(client.chat("skill", "system", "user").isPresent());
    }

    @Test
    void redactsEmailsKeysAndBearerTokens() {
        String redacted = AnalysisModelClient.redact(
                "contact a@example.com sk-abcdefghijklmnop ghp_abcdefghijklmnopqrstuvwx Bearer abcdefghijklmnop");
        assertFalse(redacted.contains("a@example.com"));
        assertFalse(redacted.contains("sk-abcdefghijklmnop"));
        assertFalse(redacted.contains("ghp_abcdefghijklmnopqrstuvwx"));
        assertFalse(redacted.contains("Bearer abcdefghijklmnop"));
        assertTrue(redacted.contains("[email]"));
        assertTrue(redacted.contains("[api-key]"));
        assertTrue(redacted.contains("[token]"));
    }

    @Test
    void extractsJsonAfterContractWrappingIsHandledByAnalyzer() throws Exception {
        String wrapped = "```json\n{\"verdict\":\"partial\",\"missedSteps\":[\"s2\"],\"confidence\":0.8}\n```";
        int start = wrapped.indexOf('{');
        int end = wrapped.lastIndexOf('}');
        java.util.Map<?, ?> parsed = mapper.readValue(wrapped.substring(start, end + 1), java.util.Map.class);
        assertEquals("partial", parsed.get("verdict"));
        assertEquals(0.8, ((Number) parsed.get("confidence")).doubleValue(), 0.0001);
    }
}
