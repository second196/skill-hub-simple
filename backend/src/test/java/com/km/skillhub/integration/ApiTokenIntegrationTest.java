package com.km.skillhub.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    void publishTokenUploadsDraftAndMissingGatesKeepItUnchanged() throws Exception {
        String readToken = createToken("审核只读", "[\"skill:read\"]");
        String publishToken = createToken("CLI 发布", "[\"skill:publish\"]");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String assetKey = "token-publish-" + suffix;
        MockMultipartFile skillPackage = new MockMultipartFile(
                "file", "skill.zip", "application/zip", zip(assetKey));

        mockMvc.perform(multipart("/api/v1/assets/imports/package/validate")
                        .file(skillPackage)
                        .header("Authorization", "Bearer " + publishToken)
                        .header("X-Request-Id", "validate-" + suffix)
                        .param("ownerScopeId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        MvcResult importResult = mockMvc.perform(multipart("/api/v1/assets/imports/package")
                        .file(skillPackage)
                        .header("Authorization", "Bearer " + publishToken)
                        .header("X-Request-Id", "upload-" + suffix)
                        .param("ownerScopeId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("DRAFT"))
                .andReturn();
        String versionDigest = objectMapper.readTree(importResult.getResponse().getContentAsString())
                .get("versionDigest").asText();
        String reviewBody = "{\"versionDigest\":\"" + versionDigest + "\",\"comment\":\"申请审核\"}";

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + readToken)
                        .contentType(APPLICATION_JSON)
                        .content(reviewBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + publishToken)
                        .header("X-Request-Id", "upload-" + suffix)
                        .contentType(APPLICATION_JSON)
                .content(reviewBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("静态扫描、评测或风险证据缺失、失败或已过期"));

        assertEquals("DRAFT", jdbcTemplate.queryForObject(
                "SELECT lifecycle_state FROM skill_version WHERE version_digest = ?",
                String.class, versionDigest));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_review_task WHERE version_digest = ?",
                Integer.class, versionDigest));
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

    private byte[] zip(String skillName) throws Exception {
        String markdown = "---\nname: " + skillName
                + "\ndescription: Token integration skill\nversion: 1.0.0\n---\n# Demo\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output);
        zip.putNextEntry(new ZipEntry("SKILL.md"));
        zip.write(markdown.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.close();
        return output.toByteArray();
    }
}
