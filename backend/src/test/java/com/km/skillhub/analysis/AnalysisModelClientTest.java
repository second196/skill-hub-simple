package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.aiconfig.AiModelConfig;
import com.km.skillhub.aiconfig.AiModelConfigRepository;
import com.km.skillhub.aiconfig.AiModelConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;

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
    void usesYmlValuesWhenConfigServiceAbsent() {
        AnalysisModelClient client = new AnalysisModelClient(
                mapper, true, "http://yml.example", "k", "yml-model", "emb", 1000, 100, 1000);
        assertTrue(client.isEnabled());
    }

    @Test
    void usesYmlValuesWhenConfigServiceReadFails() throws Exception {
        AnalysisModelClient client = new AnalysisModelClient(
                mapper, false, "http://yml.example", "", "yml-model", "emb", 1000, 100, 1000);
        injectConfigService(client, new AiModelConfigService(
                new AiModelConfigRepository(null) {
                    @Override
                    public AiModelConfig findFirst() {
                        throw new IllegalStateException("db down");
                    }
                },
                false, "http://yml.example", "", "yml-model", "emb", 1000, 100, 128000));

        // DB read failure falls back to yml: yml enabled=false keeps client off
        assertFalse(client.isEnabled());
    }

    @Test
    void databaseConfigOverridesConstructorValues() throws Exception {
        AnalysisModelClient client = new AnalysisModelClient(
                mapper, false, "", "", "yml-model", "emb", 1000, 100, 1000);
        MutableRepository repository = new MutableRepository(enabledRow());
        injectConfigService(client, new AiModelConfigService(
                repository,
                false, "", "", "yml-model", "emb", 1000, 100, 128000));

        assertTrue(client.isEnabled(), "DB enabled+endpoint must turn the client on");

        // hot update: flipping enabled off takes effect without restart
        repository.row.setEnabled(Boolean.FALSE);
        assertFalse(client.isEnabled());
    }

    @Test
    void hotReloadsAfterConfigUpdate() throws Exception {
        AnalysisModelClient client = new AnalysisModelClient(
                mapper, false, "", "", "yml-model", "emb", 1000, 100, 1000);
        MutableRepository repository = new MutableRepository(null);
        AiModelConfigService service = new AiModelConfigService(
                repository,
                true, "http://yml.example", "", "yml-model", "emb", 1000, 100, 128000);
        injectConfigService(client, service);

        // no row yet -> yml enabled=true + endpoint -> on
        assertTrue(client.isEnabled());

        // admin PUT creates a row that disables the client -> takes effect immediately
        AiModelConfig patch = new AiModelConfig();
        patch.setEnabled(Boolean.FALSE);
        service.update(patch, "system");
        assertFalse(client.isEnabled(), "update must take effect without restart");
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

    private static AiModelConfig enabledRow() {
        AiModelConfig row = new AiModelConfig();
        row.setId(1L);
        row.setEnabled(Boolean.TRUE);
        row.setEndpoint("http://db.example");
        row.setApiKey("db-key");
        row.setModel("db-model");
        row.setEmbeddingModel("db-embed");
        row.setTimeoutMs(Integer.valueOf(2000));
        row.setMaxTokens(Integer.valueOf(500));
        row.setContextWindow(Integer.valueOf(64000));
        return row;
    }

    private static void injectConfigService(AnalysisModelClient client, AiModelConfigService service)
            throws Exception {
        Field field = AnalysisModelClient.class.getDeclaredField("configService");
        field.setAccessible(true);
        field.set(client, service);
    }

    private static final class MutableRepository extends AiModelConfigRepository {
        AiModelConfig row;

        MutableRepository(AiModelConfig row) {
            super((JdbcTemplate) null);
            this.row = row;
        }

        @Override
        public AiModelConfig findFirst() {
            return row;
        }

        @Override
        public void insert(AiModelConfig config) {
            config.setId(99L);
            this.row = config;
        }

        @Override
        public void update(AiModelConfig config) {
            this.row = config;
        }
    }
}
