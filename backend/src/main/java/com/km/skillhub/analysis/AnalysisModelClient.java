package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.aiconfig.AiModelConfig;
import com.km.skillhub.aiconfig.AiModelConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional, OpenAI-compatible model client for the two LLM side paths: contract
 * extraction and L2 turn verdicts. It is disabled by default; without an endpoint it
 * returns {@link Optional#empty()} and the deterministic rules keep running.
 *
 * <p>Runtime settings resolution: {@code sys_ai_config} (via {@link AiModelConfigService})
 * wins per field; empty / invalid DB values and DB-unavailable situations fall back to the
 * application.yml {@code skillhub.analysis.llm.*} values captured in the constructor. When
 * no database config bean is present (plain unit tests), yml constructor values apply as-is.
 * Config updates take effect on the next call (no restart).
 */
@Component
public class AnalysisModelClient {

    private static final int MAX_REQUEST_CHARS = 12000;
    /** 允许的思考程度取值；网关对未知值静默忽略，故这里先拦掉。 */
    private static final List<String> REASONING_EFFORTS =
            Collections.unmodifiableList(Arrays.asList("minimal", "low", "medium", "high"));

    private final ObjectMapper mapper;
    private final boolean ymlEnabled;
    private final String ymlEndpoint;
    private final String ymlApiKey;
    private final String ymlModel;
    private final String ymlEmbeddingModel;
    private final int ymlTimeoutMs;
    private final int ymlMaxTokens;
    private final long dailyTokenBudget;
    /** 运行时配置源；未注入时（单测）回退构造参数（yml）。 */
    @Autowired(required = false)
    private AiModelConfigService configService;
    private final Semaphore permits = new Semaphore(2);
    private final Budget globalBudget = new Budget(LocalDate.now());
    private final Map<String, Long> circuitUntil = new ConcurrentHashMap<String, Long>();

    public AnalysisModelClient(ObjectMapper mapper,
                               @Value("${skillhub.analysis.llm.enabled:false}") boolean enabled,
                               @Value("${skillhub.analysis.llm.endpoint:}") String endpoint,
                               @Value("${skillhub.analysis.llm.api-key:}") String apiKey,
                               @Value("${skillhub.analysis.llm.model:gpt-4.1-mini}") String model,
                               @Value("${skillhub.analysis.llm.embedding-model:text-embedding-3-small}") String embeddingModel,
                               @Value("${skillhub.analysis.llm.timeout-ms:30000}") int timeoutMs,
                               @Value("${skillhub.analysis.llm.max-tokens:2000}") int maxTokens,
                               @Value("${skillhub.analysis.llm.daily-token-budget:50000}") long dailyTokenBudget) {
        this.mapper = mapper;
        this.ymlEnabled = enabled;
        this.ymlEndpoint = endpoint == null ? "" : endpoint.trim();
        this.ymlApiKey = apiKey == null ? "" : apiKey.trim();
        this.ymlModel = model == null || model.trim().isEmpty() ? "gpt-4.1-mini" : model.trim();
        this.ymlEmbeddingModel = embeddingModel == null || embeddingModel.trim().isEmpty()
                ? "text-embedding-3-small" : embeddingModel.trim();
        this.ymlTimeoutMs = timeoutMs <= 0 ? 30000 : timeoutMs;
        this.ymlMaxTokens = maxTokens <= 0 ? 2000 : maxTokens;
        this.dailyTokenBudget = dailyTokenBudget <= 0 ? 50000 : dailyTokenBudget;
    }

    /** 生效配置：DB 优先、yml 兜底；永不返回 null。 */
    private AiModelConfig settings() {
        if (configService != null) {
            try {
                AiModelConfig live = configService.getCurrent();
                if (live != null) return live;
            } catch (RuntimeException ignored) {
                // fall through to yml defaults
            }
        }
        AiModelConfig fallback = new AiModelConfig();
        fallback.setEnabled(Boolean.valueOf(ymlEnabled));
        fallback.setEndpoint(ymlEndpoint);
        fallback.setApiKey(ymlApiKey);
        fallback.setModel(ymlModel);
        fallback.setEmbeddingModel(ymlEmbeddingModel);
        fallback.setTimeoutMs(Integer.valueOf(ymlTimeoutMs));
        fallback.setMaxTokens(Integer.valueOf(ymlMaxTokens));
        return fallback;
    }

    private String liveEndpoint() {
        String value = settings().getEndpoint();
        if (value == null) return "";
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String liveApiKey() {
        String value = settings().getApiKey();
        return value == null ? "" : value.trim();
    }

    private String liveModel() {
        String value = settings().getModel();
        return value == null || value.trim().isEmpty() ? "gpt-4.1-mini" : value.trim();
    }

    private String liveEmbeddingModel() {
        String value = settings().getEmbeddingModel();
        return value == null || value.trim().isEmpty()
                ? "text-embedding-3-small" : value.trim();
    }

    private int liveTimeoutMs() {
        Integer value = settings().getTimeoutMs();
        return value == null || value.intValue() <= 0 ? 30000 : value.intValue();
    }

    private int liveMaxTokens() {
        Integer value = settings().getMaxTokens();
        return value == null || value.intValue() <= 0 ? 2000 : value.intValue();
    }

    /** 当前配置的思考程度；空（未配置 / 非法值）表示不传该参数。 */
    private String liveReasoningEffort() {
        String value = settings().getReasoningEffort();
        if (value == null) return "";
        String effort = value.trim();
        if (effort.isEmpty()) return "";
        return REASONING_EFFORTS.contains(effort) ? effort : "";
    }

    public boolean isEnabled() {
        AiModelConfig cfg = settings();
        boolean on = cfg.getEnabled() != null && cfg.getEnabled().booleanValue();
        return on && !liveEndpoint().isEmpty();
    }

    /** One chat completion; empty when disabled, rate-limited, or the endpoint failed. */
    public Optional<String> chat(String slug, String system, String user) {
        AiModelConfig cfg = settings();
        if (!canUse(cfg, slug, system, user)) return Optional.empty();
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("model", liveModel());
            body.put("temperature", 0);
            body.put("max_tokens", Integer.valueOf(liveMaxTokens()));
            String effort = liveReasoningEffort();
            if (!effort.isEmpty()) {
                body.put("reasoning_effort", effort);
            }
            List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
            Map<String, String> systemMessage = new LinkedHashMap<String, String>();
            systemMessage.put("role", "system");
            systemMessage.put("content", system);
            Map<String, String> userMessage = new LinkedHashMap<String, String>();
            userMessage.put("role", "user");
            userMessage.put("content", cap(user, MAX_REQUEST_CHARS));
            messages.add(systemMessage);
            messages.add(userMessage);
            body.put("messages", messages);
            String response = post(liveEndpoint() + "/chat/completions", body);
            if (response == null) {
                recordFailure(slug);
                return Optional.empty();
            }
            return extractChatText(parse(response));
        } catch (RuntimeException ex) {
            recordFailure(slug);
            return Optional.empty();
        } finally {
            permits.release();
        }
    }

    /** One embedding vector; empty when disabled, rate-limited, or the endpoint failed. */
    public Optional<List<Float>> embed(String slug, String text) {
        AiModelConfig cfg = settings();
        if (!canUse(cfg, slug, text, "")) return Optional.empty();
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("model", liveEmbeddingModel());
            body.put("input", cap(text, MAX_REQUEST_CHARS));
            String response = post(liveEndpoint() + "/embeddings", body);
            if (response == null) {
                recordFailure(slug);
                return Optional.empty();
            }
            return extractEmbedding(parse(response));
        } catch (RuntimeException ex) {
            recordFailure(slug);
            return Optional.empty();
        } finally {
            permits.release();
        }
    }

    private boolean canUse(AiModelConfig cfg, String slug, String first, String second) {
        boolean enabled = cfg.getEnabled() != null && cfg.getEnabled().booleanValue();
        if (!enabled || liveEndpoint().isEmpty()) return false;
        Long until = circuitUntil.get(slug);
        if (until != null && until.longValue() > System.currentTimeMillis()) return false;
        if (!permits.tryAcquire()) return false;
        int estimated = 800 + (first == null ? 0 : first.length()) / 4 + (second == null ? 0 : second.length()) / 4;
        if (!reserve(slug, estimated)) {
            permits.release();
            return false;
        }
        return true;
    }

    private synchronized boolean reserve(String slug, int tokens) {
        LocalDate today = LocalDate.now();
        if (!today.equals(globalBudget.date)) {
            globalBudget.date = today;
            globalBudget.tokens.set(0L);
        }
        if (globalBudget.tokens.get() + tokens > dailyTokenBudget) return false;
        globalBudget.tokens.addAndGet(tokens);
        return true;
    }

    private void recordFailure(String slug) {
        circuitUntil.put(slug, Long.valueOf(System.currentTimeMillis() + 24L * 60L * 60L * 1000L));
    }

    private String post(String url, Map<String, Object> body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            int timeoutMs = liveTimeoutMs();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            String apiKey = liveApiKey();
            if (!apiKey.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            byte[] payload = mapper.writeValueAsBytes(body);
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
            } finally {
                output.close();
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String text = stream == null ? "" : readFully(stream);
            if (code < 200 || code >= 300) return null;
            return text;
        } catch (Exception ex) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<String> extractChatText(Map<String, Object> root) {
        try {
            Object choices = root.get("choices");
            if (!(choices instanceof List) || ((List<Object>) choices).isEmpty()) return Optional.empty();
            Map<String, Object> choice = (Map<String, Object>) ((List<Object>) choices).get(0);
            Object message = choice.get("message");
            if (message instanceof Map) {
                Object content = ((Map<String, Object>) message).get("content");
                if (content != null) return Optional.of(String.valueOf(content));
            }
            Object text = choice.get("text");
            if (text != null) return Optional.of(String.valueOf(text));
        } catch (RuntimeException ignored) {
            // invalid response shape is a failure, not a crash
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<List<Float>> extractEmbedding(Map<String, Object> root) {
        try {
            Object data = root.get("data");
            if (!(data instanceof List) || ((List<Object>) data).isEmpty()) return Optional.empty();
            Map<String, Object> first = (Map<String, Object>) ((List<Object>) data).get(0);
            Object values = first.get("embedding");
            if (!(values instanceof List)) return Optional.empty();
            List<Float> out = new ArrayList<Float>();
            for (Object value : (List<Object>) values) {
                out.add(value instanceof Number ? Float.valueOf(((Number) value).floatValue()) : Float.valueOf(0f));
            }
            return Optional.of(out);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /** Redact obvious PII before anything leaves the server. */
    public static String redact(String text) {
        if (text == null) return "";
        String value = text.replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", "[email]");
        value = value.replaceAll("(?i)sk-[a-z0-9]{16,}", "[api-key]");
        value = value.replaceAll("(?i)ghp_[a-z0-9]{20,}", "[token]");
        value = value.replaceAll("(?i)Bearer\\s+[a-z0-9._~+/=-]{16,}", "Bearer [token]");
        return value;
    }

    private static String cap(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private static String readFully(InputStream stream) {
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                if (read > 0) buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static class Budget {
        volatile LocalDate date;
        final AtomicLong tokens = new AtomicLong();

        Budget(LocalDate date) {
            this.date = date;
        }
    }
}
