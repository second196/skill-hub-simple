package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional, OpenAI-compatible model client for the two LLM side paths: contract
 * extraction and L2 turn verdicts. It is disabled by default; without an endpoint it
 * returns {@link Optional#empty()} and the deterministic rules keep running.
 */
@Component
public class AnalysisModelClient {

    private static final int MAX_REQUEST_CHARS = 12000;

    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final String embeddingModel;
    private final int timeoutMs;
    private final int maxTokens;
    private final long dailyTokenBudget;
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
        this.enabled = enabled;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.trim().isEmpty() ? "gpt-4.1-mini" : model.trim();
        this.embeddingModel = embeddingModel == null || embeddingModel.trim().isEmpty()
                ? "text-embedding-3-small" : embeddingModel.trim();
        this.timeoutMs = timeoutMs <= 0 ? 30000 : timeoutMs;
        this.maxTokens = maxTokens <= 0 ? 2000 : maxTokens;
        this.dailyTokenBudget = dailyTokenBudget <= 0 ? 50000 : dailyTokenBudget;
    }

    public boolean isEnabled() {
        return enabled && !endpoint.isEmpty();
    }

    /** One chat completion; empty when disabled, rate-limited, or the endpoint failed. */
    public Optional<String> chat(String slug, String system, String user) {
        if (!canUse(slug, system, user)) return Optional.empty();
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("max_tokens", Integer.valueOf(maxTokens));
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
            String response = post(endpoint + "/chat/completions", body);
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
        if (!canUse(slug, text, "")) return Optional.empty();
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("model", embeddingModel);
            body.put("input", cap(text, MAX_REQUEST_CHARS));
            String response = post(endpoint + "/embeddings", body);
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

    private boolean canUse(String slug, String first, String second) {
        if (!isEnabled()) return false;
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
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
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
