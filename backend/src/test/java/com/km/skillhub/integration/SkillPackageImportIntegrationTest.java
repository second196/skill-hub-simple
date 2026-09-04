package com.km.skillhub.integration;

import com.km.skillhub.integration.artifact.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SkillPackageImportIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private ArtifactStore artifactStore;

    @Test
    void validatesWithoutWritesAndImportsDraftIdempotently() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String requestId = "cli-import-" + suffix;
        String assetKey = "cli-skill-" + suffix;
        byte[] archive = zip(assetKey);
        MockMultipartFile file = new MockMultipartFile("file", "skill.zip", "application/zip", archive);
        Integer before = jdbcTemplate.queryForObject("SELECT count(*) FROM skill_import_attempt", Integer.class);

        mockMvc.perform(multipart("/api/v1/assets/imports/package/validate")
                        .file(file)
                        .param("ownerScopeId", "1")
                        .with(user("admin"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.name").value(assetKey));
        assertEquals(before, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_import_attempt", Integer.class));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(multipart("/api/v1/assets/imports/package")
                            .file(file)
                            .header("X-Request-Id", requestId)
                            .param("ownerScopeId", "1")
                            .with(user("admin"))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lifecycleState").value("DRAFT"))
                    .andExpect(jsonPath("$.versionDigest").isNotEmpty());
        }

        mockMvc.perform(get("/api/v1/assets/imports/{requestId}", requestId)
                        .with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactDigest").isNotEmpty())
                .andExpect(jsonPath("$.versionDigest").isNotEmpty())
                .andExpect(jsonPath("$.lifecycleState").value("DRAFT"));

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_import_attempt WHERE request_id = ?", Integer.class, requestId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_version sv JOIN skill_asset sa ON sa.id = sv.asset_id "
                        + "WHERE sa.asset_key = ? AND sv.lifecycle_state = 'DRAFT'", Integer.class, assetKey));
    }

    @Test
    void rejectsClientDigestAndVersionContentConflicts() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String assetKey = "conflict-skill-" + suffix;
        byte[] firstArchive = zip(assetKey, "first");

        mockMvc.perform(multipart("/api/v1/assets/imports/package")
                        .file(file(firstArchive))
                        .header("X-Request-Id", "digest-mismatch-" + suffix)
                        .param("ownerScopeId", "1")
                        .param("artifactDigest", repeat('a', 64))
                        .with(user("admin"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("CLIENT_DIGEST_MISMATCH"));

        performImport("version-first-" + suffix, firstArchive)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("DRAFT"));

        performImport("version-second-" + suffix, zip(assetKey, "changed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONTENT_CONFLICT"));

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_version sv JOIN skill_asset sa ON sa.id = sv.asset_id "
                        + "WHERE sa.asset_key = ?", Integer.class, assetKey));
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM skill_import_attempt WHERE request_id = ?", String.class,
                "version-second-" + suffix));
    }

    @Test
    void rollsBackBusinessRowsWhenArtifactStorageFails() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String assetKey = "failed-skill-" + suffix;
        doThrow(new IllegalStateException("storage unavailable"))
                .when(artifactStore).store(any(byte[].class), anyString(), anyString());

        performImport("storage-failure-" + suffix, zip(assetKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("IMPORT_FAILED"));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_asset WHERE asset_key = ?", Integer.class, assetKey));
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM skill_import_attempt WHERE request_id = ?", String.class,
                "storage-failure-" + suffix));
    }

    @Test
    void serializesConcurrentUploadsForTheSameAssetVersion() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String assetKey = "concurrent-skill-" + suffix;
        byte[] archive = zip(assetKey);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<MvcResult>> requests = new ArrayList<Callable<MvcResult>>();
            requests.add(() -> performImport("concurrent-a-" + suffix, archive).andReturn());
            requests.add(() -> performImport("concurrent-b-" + suffix, archive).andReturn());
            List<Future<MvcResult>> results = executor.invokeAll(requests);
            for (Future<MvcResult> result : results) {
                assertEquals(200, result.get().getResponse().getStatus());
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_version sv JOIN skill_asset sa ON sa.id = sv.asset_id "
                        + "WHERE sa.asset_key = ?", Integer.class, assetKey));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_import_attempt WHERE request_id IN (?, ?)", Integer.class,
                "concurrent-a-" + suffix, "concurrent-b-" + suffix));
    }

    private org.springframework.test.web.servlet.ResultActions performImport(
            String requestId, byte[] archive) throws Exception {
        return mockMvc.perform(multipart("/api/v1/assets/imports/package")
                .file(file(archive))
                .header("X-Request-Id", requestId)
                .param("ownerScopeId", "1")
                .with(user("admin"))
                .with(csrf()));
    }

    private MockMultipartFile file(byte[] archive) {
        return new MockMultipartFile("file", "skill.zip", "application/zip", archive);
    }

    private byte[] zip(String skillName) throws Exception {
        return zip(skillName, null);
    }

    private byte[] zip(String skillName, String readme) throws Exception {
        String markdown = "---\nname: " + skillName + "\ndescription: Integration skill\nversion: 1.0.0\n---\n# Demo\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output);
        zip.putNextEntry(new ZipEntry("SKILL.md"));
        zip.write(markdown.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        if (readme != null) {
            zip.putNextEntry(new ZipEntry("README.md"));
            zip.write(readme.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        zip.close();
        return output.toByteArray();
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
