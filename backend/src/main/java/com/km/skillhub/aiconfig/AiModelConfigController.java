package com.km.skillhub.aiconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.skill.SkillApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 系统设置 · AI 模型运行时配置（对齐 kmplm AiModelConfigController 行为）。
 * 本仓库无鉴权体系，端点直接暴露（与 skill/observation/discovery 一致）。
 */
@RestController
@RequestMapping("/api/ai-config")
public class AiModelConfigController {

    private static final int MODELS_TIMEOUT_MS = 10000;
    public static final String CODE_AI_CONFIG_ERROR = "AI_CONFIG_ERROR";

    private final AiModelConfigService aiModelConfigService;
    private final ObjectMapper objectMapper;

    public AiModelConfigController(AiModelConfigService aiModelConfigService, ObjectMapper objectMapper) {
        this.aiModelConfigService = aiModelConfigService;
        this.objectMapper = objectMapper;
    }

    /** 当前生效配置（api_key 掩码）。 */
    @GetMapping
    public AiModelConfig get() {
        return aiModelConfigService.getMasked();
    }

    /** 部分更新；apiKey 空串或 "******" 表示不修改。更新后 LLM 客户端即时生效。 */
    @PutMapping
    public AiModelConfig update(@RequestBody(required = false) AiModelConfig patch) {
        return aiModelConfigService.update(patch, "system");
    }

    /**
     * 代理拉取 OpenAI 兼容服务的模型列表（不向前端返回 api_key）。
     * 可选 query endpoint 覆盖已存配置，供前端在未保存新地址时先探活。
     */
    @GetMapping("/models")
    public List<String> listModels(
            @RequestParam(value = "endpoint", required = false) String endpointOverride) {
        AiModelConfig config = aiModelConfigService.getCurrent();
        String endpoint = endpointOverride != null && !endpointOverride.trim().isEmpty()
                ? endpointOverride.trim()
                : (config.getEndpoint() == null ? "" : config.getEndpoint().trim());
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (endpoint.isEmpty()) {
            throw new SkillApiException("接口地址未配置，请先填写接口地址", CODE_AI_CONFIG_ERROR);
        }
        String apiKey = config.getApiKey() == null ? "" : config.getApiKey().trim();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint + "/models").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(MODELS_TIMEOUT_MS);
            connection.setReadTimeout(MODELS_TIMEOUT_MS);
            if (!apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : readFully(stream);
            if (status < 200 || status >= 300) {
                throw new SkillApiException(
                        "拉取模型列表失败：模型服务返回 HTTP " + status, CODE_AI_CONFIG_ERROR);
            }
            List<String> ids = extractModelIds(body);
            if (ids.isEmpty()) {
                throw new SkillApiException("模型服务未返回可用模型列表", CODE_AI_CONFIG_ERROR);
            }
            return ids;
        } catch (SkillApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SkillApiException(
                    "拉取模型列表失败：无法连接模型服务（" + ex.getClass().getSimpleName() + "）",
                    CODE_AI_CONFIG_ERROR);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 非对话能力标记（出现在 supported_endpoint_types 即排除）。 */
    private static final String[] NON_CHAT_TYPES = {
        "image-generation", "video-generation", "audio-generation",
        "text-to-speech", "speech-to-text", "tts", "stt", "asr",
        "embedding", "moderation", "rerank",
        "image", "video", "audio"
    };

    /** 无能力字段时的名称兜底（仅在条目未声明 supported_endpoint_types 时生效）。 */
    private static final String[] NON_CHAT_NAME_TOKENS = {
        "image", "dall-e", "dalle", "whisper", "tts", "stt",
        "embedding", "rerank", "moderation", "audio", "video",
        "flux", "stable-diffusion", "sdxl"
    };

    /** 从 OpenAI 兼容 {"data":[{"id":...}]} 提取模型 id，排除生图/音频/向量等非对话模型。 */
    @SuppressWarnings("unchecked")
    private List<String> extractModelIds(String body) {
        List<String> ids = new ArrayList<String>();
        try {
            Map<String, Object> root = objectMapper.readValue(body, Map.class);
            Object data = root.get("data");
            if (!(data instanceof List)) {
                data = root.get("models");
            }
            if (!(data instanceof List)) return ids;
            for (Object item : (List<Object>) data) {
                if (item instanceof String) {
                    String value = ((String) item).trim();
                    if (!value.isEmpty() && looksLikeChatName(value)) {
                        addIfAbsent(ids, value);
                    }
                    continue;
                }
                if (!(item instanceof Map)) continue;
                Map<String, Object> entry = (Map<String, Object>) item;
                String id = null;
                for (String field : new String[] {"id", "model", "name"}) {
                    Object value = entry.get(field);
                    if (value instanceof String && !((String) value).trim().isEmpty()) {
                        id = ((String) value).trim();
                        break;
                    }
                }
                if (id == null) continue;
                if (declaresNonChatCapability(entry)) continue;
                if (!looksLikeChatName(id)) continue;
                addIfAbsent(ids, id);
            }
        } catch (SkillApiException ex) {
            throw ex;
        } catch (Exception ignored) {
            // 解析失败按空列表处理，由调用方转成业务错误
        }
        return ids;
    }

    /** 条目是否声明了非对话能力（supported_endpoint_types / type / capability 等字段）。 */
    @SuppressWarnings("unchecked")
    private static boolean declaresNonChatCapability(Map<String, Object> entry) {
        for (String field : new String[] {"supported_endpoint_types", "capabilities", "capability", "type"}) {
            Object value = entry.get(field);
            if (value instanceof String) {
                if (containsNonChatToken(((String) value).toLowerCase())) return true;
            } else if (value instanceof List) {
                for (Object token : (List<Object>) value) {
                    if (token instanceof String && containsNonChatToken(((String) token).toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsNonChatToken(String lower) {
        for (String token : NON_CHAT_TYPES) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    /** 名称兜底：命中生图/音频/向量等词根则按非对话模型排除。 */
    private static boolean looksLikeChatName(String id) {
        String lower = id.toLowerCase();
        for (String token : NON_CHAT_NAME_TOKENS) {
            if (lower.contains(token)) return false;
        }
        return true;
    }

    private static void addIfAbsent(List<String> ids, String value) {
        if (!ids.contains(value)) {
            ids.add(value);
        }
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
}
