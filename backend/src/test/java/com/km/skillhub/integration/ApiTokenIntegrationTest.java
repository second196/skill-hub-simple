package com.km.skillhub.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ApiTokenIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTokens() {
        jdbcTemplate.update("DELETE FROM api_token");
    }

    @Test
    void createsTokenWithoutPersistingPlaintextAndListsOnlyMetadata() throws Exception {
        String token = createToken("目录读取", "[\"skill:read\"]");

        assertTrue(token.startsWith("sk_"));
        String hash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM api_token WHERE name = ?", String.class, "目录读取");
        assertNotEquals(token, hash);
        assertFalse(jdbcTemplate.queryForObject(
                "SELECT scope_json::text FROM api_token WHERE name = ?", String.class, "目录读取").contains(token));

        mockMvc.perform(get("/api/v1/tokens").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("目录读取"))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andExpect(jsonPath("$[0].tokenPrefix").value(token.substring(0, 12)));
    }

    @Test
    void enforcesBearerScopesAndCsrfBoundaries() throws Exception {
        String readToken = createToken("仅读取", "[\"skill:read\"]");
        String publishToken = createToken("仅发布", "[\"skill:publish\"]");

        mockMvc.perform(get("/api/v1/assets")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/assets/imports")
                        .header("Authorization", "Bearer " + readToken)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/assets")
                        .header("Authorization", "Bearer " + publishToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/tokens")
                        .with(user("admin"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"缺少 CSRF\",\"scopes\":[\"skill:read\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedTokenCannotReadAssets() throws Exception {
        String token = createToken("待撤销", "[\"skill:read\"]");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM api_token WHERE name = ?", Long.class, "待撤销");

        mockMvc.perform(delete("/api/v1/tokens/{id}", id)
                        .with(user("admin"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/assets")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsupportedBearerTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/assets").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    private String createToken(String name, String scopes) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tokens")
                        .with(user("admin"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"scopes\":" + scopes + "}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        return payload.get("token").asText();
    }
}
