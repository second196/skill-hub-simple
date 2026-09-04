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
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SkillCliContractIntegrationTest {
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
    void bearerUploadRetryAndReviewProduceOneGovernedCandidate() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String requestId = "cli-contract-" + suffix;
        String assetKey = "cli-contract-skill-" + suffix;
        String publishToken = createToken("CLI 契约 " + suffix, "[\"skill:publish\"]");
        MockMultipartFile skillPackage = new MockMultipartFile(
                "file", "skill.zip", "application/zip", zip(assetKey));

        mockMvc.perform(multipart("/api/v1/assets/imports/package/validate")
                        .file(skillPackage)
                        .header("Authorization", "Bearer " + publishToken)
                        .header("X-Request-Id", requestId)
                        .param("ownerScopeId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        MvcResult firstUpload = upload(skillPackage, publishToken, requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("DRAFT"))
                .andReturn();
        String versionDigest = objectMapper.readTree(firstUpload.getResponse().getContentAsString())
                .get("versionDigest").asText();
        upload(skillPackage, publishToken, requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.versionDigest").value(versionDigest));

        insertPassingEvidence(versionDigest, suffix);
        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + publishToken)
                        .header("X-Request-Id", requestId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"versionDigest\":\"" + versionDigest
                                + "\",\"comment\":\"CLI 契约审核\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.versionDigest").value(versionDigest));

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_import_attempt WHERE request_id = ?",
                Integer.class, requestId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_version WHERE version_digest = ? AND lifecycle_state = 'CANDIDATE'",
                Integer.class, versionDigest));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_review_task WHERE version_digest = ? AND status = 'PENDING'",
                Integer.class, versionDigest));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'SUBMIT_REVIEW' AND object_id = ?",
                Integer.class, versionDigest));
    }

    private org.springframework.test.web.servlet.ResultActions upload(
            MockMultipartFile skillPackage, String token, String requestId) throws Exception {
        return mockMvc.perform(multipart("/api/v1/assets/imports/package")
                .file(skillPackage)
                .header("Authorization", "Bearer " + token)
                .header("X-Request-Id", requestId)
                .param("ownerScopeId", "1"));
    }

    private void insertPassingEvidence(String versionDigest, String suffix) {
        for (String type : new String[] {"STATIC_SCAN", "EVALUATION", "RISK"}) {
            int rows = jdbcTemplate.update(
                    "INSERT INTO gate_evidence(version_digest, evidence_type, result, producer_type, producer_id, "
                            + "evidence_digest, conditions, generated_at, expires_at) "
                            + "VALUES (?, ?, 'PASS', 'SERVICE', 'contract', ?, '{}'::jsonb, "
                            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                    versionDigest, type, sha256(type + suffix));
            assertEquals(1, rows);
        }
    }

    private String createToken(String name, String scopes) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tokens")
                        .with(user("admin"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"scopes\":" + scopes + "}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token");
        assertNotNull(token);
        return token.asText();
    }

    private byte[] zip(String skillName) throws Exception {
        String markdown = "---\nname: " + skillName
                + "\ndescription: CLI contract skill\nversion: 1.0.0\n---\n# Demo\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output);
        zip.putNextEntry(new ZipEntry("SKILL.md"));
        zip.write(markdown.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.close();
        return output.toByteArray();
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder digest = new StringBuilder(64);
            for (byte item : bytes) digest.append(String.format("%02x", item & 0xff));
            return digest.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
