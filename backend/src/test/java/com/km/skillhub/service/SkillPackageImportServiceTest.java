package com.km.skillhub.service;

import com.km.skillhub.asset.model.dto.SkillPackageImportCommand;
import com.km.skillhub.asset.model.entity.SkillArtifactEntity;
import com.km.skillhub.asset.model.entity.SkillAssetEntity;
import com.km.skillhub.asset.model.entity.SkillImportAttemptEntity;
import com.km.skillhub.asset.model.vo.ImportAttemptVO;
import com.km.skillhub.asset.service.SkillPackageConflictException;
import com.km.skillhub.asset.service.SkillPackageValidator;
import com.km.skillhub.asset.service.impl.SkillPackageImportServiceImpl;
import com.km.skillhub.integration.artifact.ArtifactStore;
import com.km.skillhub.mapper.asset.SkillArtifactMapper;
import com.km.skillhub.mapper.asset.SkillAssetMapper;
import com.km.skillhub.mapper.asset.SkillImportAttemptMapper;
import com.km.skillhub.mapper.content.SkillVersionFileMapper;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillPackageImportServiceTest {

    @Test
    void importsValidatedPackageAsDraftWithSeparateDigests() throws Exception {
        Fixture fixture = fixture();
        byte[] archive = zip(skillMarkdown(), "README.md", "text");

        ImportAttemptVO result = fixture.service.importPackage(command(archive), "admin");

        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals("DRAFT", result.getLifecycleState());
        assertNotEquals(result.getArtifactDigest(), result.getVersionDigest());
        assertEquals(7L, result.getAssetId());
        ArgumentCaptor<SkillVersionEntity> versionCaptor = ArgumentCaptor.forClass(SkillVersionEntity.class);
        verify(fixture.versions).insertVersion(versionCaptor.capture());
        assertEquals("DRAFT", versionCaptor.getValue().getLifecycleState());
        verify(fixture.files, org.mockito.Mockito.times(2)).insertFile(any());
    }

    @Test
    void rejectsSameRequestIdWithDifferentPayloadDigest() throws Exception {
        Fixture fixture = fixture();
        SkillImportAttemptEntity existing = new SkillImportAttemptEntity();
        existing.setRequestId("request-1");
        existing.setRequestDigest(repeat('a', 64));
        existing.setStatus("SUCCEEDED");
        when(fixture.attempts.findByRequestId("request-1")).thenReturn(existing);

        SkillPackageConflictException exception = assertThrows(SkillPackageConflictException.class,
                () -> fixture.service.importPackage(command(zip(skillMarkdown(), "README.md", "changed")), "admin"));

        assertEquals("IDEMPOTENCY_CONFLICT", exception.getCode());
    }

    @Test
    void rejectsTraversalEntryAndLeavesFailedImportTrace() throws Exception {
        Fixture fixture = fixture();

        ImportAttemptVO result = fixture.service.importPackage(
                command(zip(skillMarkdown(), "../secret.txt", "bad")), "admin");

        assertEquals("FAILED", result.getStatus());
        assertEquals("UNSAFE_PACKAGE_PATH", result.getFailureCode());
        assertTrue(result.getFailureReason().contains("路径"));
        verify(fixture.attempts).updateResult(any(SkillImportAttemptEntity.class));
    }

    private Fixture fixture() {
        SkillImportAttemptMapper attempts = mock(SkillImportAttemptMapper.class);
        SkillAssetMapper assets = mock(SkillAssetMapper.class);
        SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
        SkillVersionMapper versions = mock(SkillVersionMapper.class);
        SkillVersionFileMapper files = mock(SkillVersionFileMapper.class);
        ArtifactStore store = mock(ArtifactStore.class);
        when(attempts.insertAttempt(any(SkillImportAttemptEntity.class))).thenReturn(1);
        when(store.store(any(byte[].class), anyString(), anyString()))
                .thenReturn(new ArtifactStore.ArtifactLocation("local://digest", 128, "application/zip"));
        when(assets.findByAssetKey("demo-skill")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<SkillAssetEntity>getArgument(0).setId(7L);
            return 1;
        }).when(assets).insertAsset(any(SkillAssetEntity.class));
        doAnswer(invocation -> {
            invocation.<SkillArtifactEntity>getArgument(0).setId(8L);
            return 1;
        }).when(artifacts).insertArtifact(any(SkillArtifactEntity.class));
        doAnswer(invocation -> {
            invocation.<SkillVersionEntity>getArgument(0).setId(9L);
            return 1;
        }).when(versions).insertVersion(any(SkillVersionEntity.class));
        SkillPackageValidator validator = new SkillPackageValidator(1024 * 1024, 2 * 1024 * 1024,
                1024 * 1024, 100, 255);
        SkillPackageImportServiceImpl service = new SkillPackageImportServiceImpl(
                attempts, assets, artifacts, versions, files, store, validator, directTransactions());
        return new Fixture(attempts, versions, files, service);
    }

    private TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };
    }

    private SkillPackageImportCommand command(byte[] bytes) {
        SkillPackageImportCommand command = new SkillPackageImportCommand();
        command.setRequestId("request-1");
        command.setAssetKey("demo-skill");
        command.setName("demo-skill");
        command.setDescription("Demo skill");
        command.setOwnerScopeId(1L);
        command.setVersionLabel("1.0.0");
        command.setSourceLocator("demo.zip");
        command.setFilename("demo.zip");
        command.setPackageBytes(bytes);
        return command;
    }

    private byte[] zip(String skillContent, String secondPath, String secondContent) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output);
        addEntry(zip, "SKILL.md", skillContent);
        addEntry(zip, secondPath, secondContent);
        zip.close();
        return output.toByteArray();
    }

    private void addEntry(ZipOutputStream zip, String path, String content) throws Exception {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String skillMarkdown() {
        return "---\nname: demo-skill\ndescription: Demo skill\nversion: 1.0.0\n---\n# Demo\n";
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static class Fixture {
        private final SkillImportAttemptMapper attempts;
        private final SkillVersionMapper versions;
        private final SkillVersionFileMapper files;
        private final SkillPackageImportServiceImpl service;

        private Fixture(SkillImportAttemptMapper attempts, SkillVersionMapper versions,
                        SkillVersionFileMapper files, SkillPackageImportServiceImpl service) {
            this.attempts = attempts;
            this.versions = versions;
            this.files = files;
            this.service = service;
        }
    }
}
