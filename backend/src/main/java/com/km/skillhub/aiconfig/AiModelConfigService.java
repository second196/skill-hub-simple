package com.km.skillhub.aiconfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 模型运行时配置：单例 sys_ai_config 的查询 / 更新。
 * 读取时 DB 值优先，空值回退 application.yml（skillhub.analysis.llm.*）；
 * DB 不可用时整体回退 yml，保证无数据库配置时行为与纯 yml 一致。
 */
@Service
public class AiModelConfigService {

    public static final String MASK = "******";

    private final AiModelConfigRepository repository;

    private final boolean defaultEnabled;
    private final String defaultEndpoint;
    private final String defaultApiKey;
    private final String defaultModel;
    private final String defaultEmbeddingModel;
    private final int defaultTimeoutMs;
    private final int defaultMaxTokens;
    private final int defaultContextWindow;

    public AiModelConfigService(
            AiModelConfigRepository repository,
            @Value("${skillhub.analysis.llm.enabled:false}") boolean defaultEnabled,
            @Value("${skillhub.analysis.llm.endpoint:}") String defaultEndpoint,
            @Value("${skillhub.analysis.llm.api-key:}") String defaultApiKey,
            @Value("${skillhub.analysis.llm.model:gpt-4.1-mini}") String defaultModel,
            @Value("${skillhub.analysis.llm.embedding-model:text-embedding-3-small}") String defaultEmbeddingModel,
            @Value("${skillhub.analysis.llm.timeout-ms:30000}") int defaultTimeoutMs,
            @Value("${skillhub.analysis.llm.max-tokens:2000}") int defaultMaxTokens,
            @Value("${skillhub.analysis.llm.context-window:128000}") int defaultContextWindow) {
        this.repository = repository;
        this.defaultEnabled = defaultEnabled;
        this.defaultEndpoint = defaultEndpoint == null ? "" : defaultEndpoint.trim();
        this.defaultApiKey = defaultApiKey == null ? "" : defaultApiKey.trim();
        this.defaultModel = defaultModel == null || defaultModel.trim().isEmpty()
                ? "gpt-4.1-mini" : defaultModel.trim();
        this.defaultEmbeddingModel = defaultEmbeddingModel == null || defaultEmbeddingModel.trim().isEmpty()
                ? "text-embedding-3-small" : defaultEmbeddingModel.trim();
        this.defaultTimeoutMs = defaultTimeoutMs <= 0 ? 30000 : defaultTimeoutMs;
        this.defaultMaxTokens = defaultMaxTokens <= 0 ? 2000 : defaultMaxTokens;
        this.defaultContextWindow = defaultContextWindow <= 0 ? 128000 : defaultContextWindow;
    }

    /** 生效配置：DB 行逐字段覆盖 yml；无行 / 读取失败回退 yml。永不返回 null。 */
    public AiModelConfig getCurrent() {
        AiModelConfig row = null;
        try {
            row = repository.findFirst();
        } catch (RuntimeException ex) {
            row = null;
        }
        return merge(row);
    }

    /** 对外展示：api_key 掩码，不回传明文。 */
    public AiModelConfig getMasked() {
        AiModelConfig copy = copyOf(getCurrent());
        if (copy.getApiKey() != null && !copy.getApiKey().isEmpty()) {
            copy.setApiKey(MASK);
        }
        return copy;
    }

    /**
     * 部分更新（null 字段不变；apiKey 为空串或掩码表示不修改）。
     * 无行时以当前生效配置为基线落库，保证后续 DB 值自洽。
     */
    public AiModelConfig update(AiModelConfig patch, String operatorName) {
        AiModelConfig row;
        try {
            row = repository.findFirst();
        } catch (RuntimeException ex) {
            throw ex;
        }
        if (row == null) {
            row = copyOf(getCurrent());
            row.setId(null);
        }
        if (patch != null) {
            if (patch.getEnabled() != null) {
                row.setEnabled(Boolean.TRUE.equals(patch.getEnabled()));
            }
            if (patch.getEndpoint() != null) {
                row.setEndpoint(patch.getEndpoint().trim());
            }
            if (patch.getApiKey() != null) {
                String key = patch.getApiKey().trim();
                if (!key.isEmpty() && !MASK.equals(key)) {
                    row.setApiKey(key);
                }
            }
            if (patch.getModel() != null && !patch.getModel().trim().isEmpty()) {
                row.setModel(patch.getModel().trim());
            }
            if (patch.getEmbeddingModel() != null && !patch.getEmbeddingModel().trim().isEmpty()) {
                row.setEmbeddingModel(patch.getEmbeddingModel().trim());
            }
            if (patch.getTimeoutMs() != null && patch.getTimeoutMs().intValue() > 0) {
                row.setTimeoutMs(patch.getTimeoutMs());
            }
            if (patch.getMaxTokens() != null && patch.getMaxTokens().intValue() > 0) {
                row.setMaxTokens(patch.getMaxTokens());
            }
            if (patch.getContextWindow() != null && patch.getContextWindow().intValue() > 0) {
                row.setContextWindow(patch.getContextWindow());
            }
            if (patch.getReasoningEffort() != null) {
                // 空串 = 清空（不传参数），由模型/网关决定
                String effort = patch.getReasoningEffort().trim();
                row.setReasoningEffort(effort.isEmpty() ? null : effort);
            }
        }
        row.setUpdatedBy(operatorName == null || operatorName.trim().isEmpty()
                ? "system" : operatorName.trim());
        if (row.getId() == null) {
            repository.insert(row);
        } else {
            repository.update(row);
        }
        return getMasked();
    }

    /** DB 行逐字段覆盖 yml 默认值；空 / 非法值回退 yml。 */
    private AiModelConfig merge(AiModelConfig row) {
        AiModelConfig out = new AiModelConfig();
        out.setId(row == null ? null : row.getId());
        out.setEnabled(row != null && row.getEnabled() != null
                ? row.getEnabled() : Boolean.valueOf(defaultEnabled));
        out.setEndpoint(pick(row == null ? null : row.getEndpoint(), defaultEndpoint));
        out.setApiKey(pick(row == null ? null : row.getApiKey(), defaultApiKey));
        out.setModel(pick(row == null ? null : row.getModel(), defaultModel));
        out.setEmbeddingModel(pick(row == null ? null : row.getEmbeddingModel(), defaultEmbeddingModel));
        out.setTimeoutMs(pickPositive(row == null ? null : row.getTimeoutMs(), defaultTimeoutMs));
        out.setMaxTokens(pickPositive(row == null ? null : row.getMaxTokens(), defaultMaxTokens));
        out.setContextWindow(pickPositive(row == null ? null : row.getContextWindow(), defaultContextWindow));
        // yml 无对应键：空即不传参数
        out.setReasoningEffort(row != null && row.getReasoningEffort() != null
                && !row.getReasoningEffort().trim().isEmpty()
                ? row.getReasoningEffort().trim() : null);
        if (row != null) {
            out.setUpdatedBy(row.getUpdatedBy());
            out.setUpdatedAt(row.getUpdatedAt());
            out.setCreatedAt(row.getCreatedAt());
        }
        return out;
    }

    private static String pick(String dbValue, String ymlValue) {
        return dbValue != null && !dbValue.trim().isEmpty() ? dbValue.trim() : ymlValue;
    }

    private static int pickPositive(Integer dbValue, int ymlValue) {
        return dbValue != null && dbValue.intValue() > 0 ? dbValue.intValue() : ymlValue;
    }

    private static AiModelConfig copyOf(AiModelConfig src) {
        AiModelConfig copy = new AiModelConfig();
        copy.setId(src.getId());
        copy.setEnabled(src.getEnabled());
        copy.setEndpoint(src.getEndpoint());
        copy.setApiKey(src.getApiKey());
        copy.setModel(src.getModel());
        copy.setEmbeddingModel(src.getEmbeddingModel());
        copy.setTimeoutMs(src.getTimeoutMs());
        copy.setMaxTokens(src.getMaxTokens());
        copy.setContextWindow(src.getContextWindow());
        copy.setReasoningEffort(src.getReasoningEffort());
        copy.setUpdatedBy(src.getUpdatedBy());
        copy.setUpdatedAt(src.getUpdatedAt());
        copy.setCreatedAt(src.getCreatedAt());
        return copy;
    }
}
