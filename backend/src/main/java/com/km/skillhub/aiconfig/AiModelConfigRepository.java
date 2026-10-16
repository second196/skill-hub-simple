package com.km.skillhub.aiconfig;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * sys_ai_config 单例行的 JdbcTemplate 访问（不使用 ORM）。
 */
@Repository
public class AiModelConfigRepository {

    private static final String COLUMNS =
            "id,enabled,endpoint,api_key,model,embedding_model,timeout_ms,max_tokens,"
                    + "context_window,reasoning_effort,updated_by,updated_at,created_at";

    private static final RowMapper<AiModelConfig> MAPPER = new RowMapper<AiModelConfig>() {
        @Override
        public AiModelConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            AiModelConfig config = new AiModelConfig();
            config.setId(rs.getLong("id"));
            config.setEnabled(rs.getBoolean("enabled"));
            config.setEndpoint(rs.getString("endpoint"));
            config.setApiKey(rs.getString("api_key"));
            config.setModel(rs.getString("model"));
            config.setEmbeddingModel(rs.getString("embedding_model"));
            config.setTimeoutMs(rs.getInt("timeout_ms"));
            config.setMaxTokens(rs.getInt("max_tokens"));
            config.setContextWindow(rs.getInt("context_window"));
            config.setReasoningEffort(rs.getString("reasoning_effort"));
            config.setUpdatedBy(rs.getString("updated_by"));
            config.setUpdatedAt(rs.getObject("updated_at", java.time.OffsetDateTime.class));
            config.setCreatedAt(rs.getObject("created_at", java.time.OffsetDateTime.class));
            return config;
        }
    };

    private final JdbcTemplate jdbc;

    public AiModelConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 单例行；表为空时返回 null。 */
    public AiModelConfig findFirst() {
        List<AiModelConfig> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM sys_ai_config ORDER BY id LIMIT 1", MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(AiModelConfig config) {
        jdbc.update("INSERT INTO sys_ai_config (enabled,endpoint,api_key,model,embedding_model,"
                        + "timeout_ms,max_tokens,context_window,reasoning_effort,updated_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                config.getEnabled(), config.getEndpoint(), config.getApiKey(), config.getModel(),
                config.getEmbeddingModel(), config.getTimeoutMs(), config.getMaxTokens(),
                config.getContextWindow(), config.getReasoningEffort(), config.getUpdatedBy());
    }

    public void update(AiModelConfig config) {
        jdbc.update("UPDATE sys_ai_config SET enabled=?,endpoint=?,api_key=?,model=?,"
                        + "embedding_model=?,timeout_ms=?,max_tokens=?,context_window=?,"
                        + "reasoning_effort=?,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                config.getEnabled(), config.getEndpoint(), config.getApiKey(), config.getModel(),
                config.getEmbeddingModel(), config.getTimeoutMs(), config.getMaxTokens(),
                config.getContextWindow(), config.getReasoningEffort(), config.getUpdatedBy(),
                config.getId());
    }
}
