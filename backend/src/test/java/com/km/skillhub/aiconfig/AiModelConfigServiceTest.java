package com.km.skillhub.aiconfig;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelConfigServiceTest {

    /** yml 回退基线：enabled=true, endpoint=yml.example, model=yml-model, ... */
    private AiModelConfigService service(FakeRepository repository) {
        return new AiModelConfigService(repository,
                true, "https://yml.example/v1", "yml-key", "yml-model", "yml-embed",
                1111, 222, 128000);
    }

    @Test
    void fallsBackToYmlWhenNoDatabaseRow() {
        AiModelConfigService service = service(new FakeRepository(null));

        AiModelConfig config = service.getCurrent();

        assertTrue(config.getEnabled());
        assertEquals("https://yml.example/v1", config.getEndpoint());
        assertEquals("yml-key", config.getApiKey());
        assertEquals("yml-model", config.getModel());
        assertEquals("yml-embed", config.getEmbeddingModel());
        assertEquals(1111, config.getTimeoutMs().intValue());
        assertEquals(222, config.getMaxTokens().intValue());
    }

    @Test
    void fallsBackToYmlWhenDatabaseReadFails() {
        AiModelConfigService service = service(new FakeRepository(null) {
            @Override
            public AiModelConfig findFirst() {
                throw new IllegalStateException("db down");
            }
        });

        AiModelConfig config = service.getCurrent();

        assertTrue(config.getEnabled());
        assertEquals("https://yml.example/v1", config.getEndpoint());
        assertEquals("yml-model", config.getModel());
    }

    @Test
    void databaseValuesOverrideYmlWhenPresent() {
        AiModelConfig row = new AiModelConfig();
        row.setId(7L);
        row.setEnabled(Boolean.TRUE);
        row.setEndpoint("https://db.example/v1");
        row.setApiKey("db-key");
        row.setModel("db-model");
        row.setEmbeddingModel("db-embed");
        row.setTimeoutMs(Integer.valueOf(3000));
        row.setMaxTokens(Integer.valueOf(4000));
        row.setContextWindow(Integer.valueOf(200000));
        row.setReasoningEffort("high");
        AiModelConfigService service = service(new FakeRepository(row));

        AiModelConfig config = service.getCurrent();

        assertEquals("https://db.example/v1", config.getEndpoint());
        assertEquals("db-key", config.getApiKey());
        assertEquals("db-model", config.getModel());
        assertEquals("db-embed", config.getEmbeddingModel());
        assertEquals(3000, config.getTimeoutMs().intValue());
        assertEquals(4000, config.getMaxTokens().intValue());
        assertEquals(200000, config.getContextWindow().intValue());
        assertEquals("high", config.getReasoningEffort());
    }

    @Test
    void emptyDatabaseFieldsFallBackToYmlPerField() {
        AiModelConfig row = new AiModelConfig();
        row.setId(8L);
        row.setEnabled(Boolean.FALSE);
        row.setEndpoint("");
        row.setApiKey("   ");
        row.setModel(null);
        row.setEmbeddingModel("db-embed");
        row.setTimeoutMs(Integer.valueOf(0));
        row.setMaxTokens(null);
        row.setContextWindow(Integer.valueOf(-1));
        row.setReasoningEffort("");
        AiModelConfigService service = service(new FakeRepository(row));

        AiModelConfig config = service.getCurrent();

        // present DB field wins
        assertFalse(config.getEnabled());
        assertEquals("db-embed", config.getEmbeddingModel());
        // blank / invalid DB fields fall back to yml
        assertEquals("https://yml.example/v1", config.getEndpoint());
        assertEquals("yml-key", config.getApiKey());
        assertEquals("yml-model", config.getModel());
        assertEquals(1111, config.getTimeoutMs().intValue());
        assertEquals(222, config.getMaxTokens().intValue());
        assertEquals(128000, config.getContextWindow().intValue());
        assertNull(config.getReasoningEffort());
    }

    @Test
    void masksApiKeyInMaskedView() {
        AiModelConfig row = new AiModelConfig();
        row.setId(9L);
        row.setEnabled(Boolean.TRUE);
        row.setEndpoint("https://db.example");
        row.setApiKey("secret-key");
        row.setModel("m");
        row.setEmbeddingModel("e");
        row.setTimeoutMs(Integer.valueOf(1));
        row.setMaxTokens(Integer.valueOf(1));
        row.setContextWindow(Integer.valueOf(1));
        AiModelConfigService service = service(new FakeRepository(row));

        assertEquals(AiModelConfigService.MASK, service.getMasked().getApiKey());
        assertEquals("secret-key", service.getCurrent().getApiKey());
    }

    @Test
    void updateInsertsRowSeededFromYmlWhenAbsent() {
        FakeRepository repository = new FakeRepository(null);
        AiModelConfigService service = service(repository);

        AiModelConfig patch = new AiModelConfig();
        patch.setEnabled(Boolean.TRUE);
        patch.setModel("new-model");
        AiModelConfig masked = service.update(patch, "admin");

        assertTrue(repository.inserted.get(), "expected an insert");
        AiModelConfig saved = repository.saved.get();
        assertEquals("new-model", saved.getModel());
        // seeded from yml fallback for fields the patch did not touch
        assertEquals("https://yml.example/v1", saved.getEndpoint());
        assertEquals("yml-key", saved.getApiKey());
        assertEquals("admin", saved.getUpdatedBy());
        assertEquals(AiModelConfigService.MASK, masked.getApiKey());
    }

    @Test
    void updateMasksApiKeyAndIgnoresMaskedOrBlankKey() {
        AiModelConfig row = new AiModelConfig();
        row.setId(1L);
        row.setEnabled(Boolean.TRUE);
        row.setEndpoint("https://db.example");
        row.setApiKey("keep-me");
        row.setModel("m");
        row.setEmbeddingModel("e");
        row.setTimeoutMs(Integer.valueOf(1));
        row.setMaxTokens(Integer.valueOf(1));
        row.setContextWindow(Integer.valueOf(1));
        FakeRepository repository = new FakeRepository(row);
        AiModelConfigService service = service(repository);

        AiModelConfig patch = new AiModelConfig();
        patch.setApiKey(AiModelConfigService.MASK);
        patch.setReasoningEffort("  ");
        AiModelConfig masked = service.update(patch, null);

        assertFalse(repository.inserted.get(), "existing row must be updated, not inserted");
        assertEquals("keep-me", repository.saved.get().getApiKey());
        // blank reasoning effort normalises to null (param omitted)
        assertNull(repository.saved.get().getReasoningEffort());
        assertEquals("system", repository.saved.get().getUpdatedBy());
        assertEquals(AiModelConfigService.MASK, masked.getApiKey());
    }

    private static class FakeRepository extends AiModelConfigRepository {
        private final AiModelConfig row;
        final AtomicReference<AiModelConfig> saved = new AtomicReference<AiModelConfig>();
        final AtomicReference<Boolean> inserted = new AtomicReference<Boolean>(Boolean.FALSE);

        FakeRepository(AiModelConfig row) {
            super((JdbcTemplate) null);
            this.row = row;
        }

        @Override
        public AiModelConfig findFirst() {
            return row;
        }

        @Override
        public void insert(AiModelConfig config) {
            inserted.set(Boolean.TRUE);
            saved.set(config);
        }

        @Override
        public void update(AiModelConfig config) {
            saved.set(config);
        }
    }
}
