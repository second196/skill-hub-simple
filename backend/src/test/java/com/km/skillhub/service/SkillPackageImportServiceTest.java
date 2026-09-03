package com.km.skillhub.service;

import com.km.skillhub.asset.model.dto.SkillPackageImportCommand;
import com.km.skillhub.asset.model.entity.SkillAssetEntity;
import com.km.skillhub.asset.model.entity.SkillArtifactEntity;
import com.km.skillhub.asset.model.entity.SkillImportAttemptEntity;
import com.km.skillhub.asset.model.vo.ImportAttemptVO;
import com.km.skillhub.asset.service.impl.SkillPackageImportServiceImpl;
import com.km.skillhub.integration.artifact.ArtifactStore;
import com.km.skillhub.mapper.asset.SkillArtifactMapper;
import com.km.skillhub.mapper.asset.SkillAssetMapper;
import com.km.skillhub.mapper.asset.SkillImportAttemptMapper;
import com.km.skillhub.mapper.content.SkillVersionFileMapper;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillPackageImportServiceTest {

    @Test
    void importsSkillPackageAndCreatesFileManifest() throws Exception {
        SkillImportAttemptMapper attempts = mock(SkillImportAttemptMapper.class);
        SkillAssetMapper assets = mock(SkillAssetMapper.class);
        SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
        SkillVersionMapper versions = mock(SkillVersionMapper.class);
        SkillVersionFileMapper files = mock(SkillVersionFileMapper.class);
        ArtifactStore store = mock(ArtifactStore.class);
        when(store.store(any(byte[].class), anyString(), anyString()))
                .thenReturn(new ArtifactStore.ArtifactLocation("local://digest", 128, "application/zip"));
        when(assets.findByAssetKey("demo")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<SkillAssetEntity>getArgument(0).setId(7L);
            return 1;
        }).when(assets).insertAsset(any(SkillAssetEntity.class));
        doAnswer(invocation -> {
            invocation.<SkillArtifactEntity>getArgument(0).setId(8L);
            return 1;
        }).when(artifacts).insertArtifact(any(SkillArtifactEntity.class));
        doAnswer(invocation -> {
            invocation.<com.km.skillhub.version.model.entity.SkillVersionEntity>getArgument(0).setId(9L);
            return 1;
        }).when(versions).insertVersion(any(com.km.skillhub.version.model.entity.SkillVersionEntity.class));

        SkillPackageImportServiceImpl service = new SkillPackageImportServiceImpl(
                attempts, assets, artifacts, versions, files, store, 1024 * 1024);
        ImportAttemptVO result = service.importPackage(command(zip("SKILL.md", "# Demo", "README.md", "text")), "admin");

        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(7L, result.getAssetId());
        verify(files, org.mockito.Mockito.times(2)).insertFile(any());
    }

    @Test
    void rejectsTraversalEntryAndLeavesFailedImportTrace() throws Exception {
        SkillImportAttemptMapper attempts = mock(SkillImportAttemptMapper.class);
        SkillAssetMapper assets = mock(SkillAssetMapper.class);
        SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
        SkillVersionMapper versions = mock(SkillVersionMapper.class);
        SkillVersionFileMapper files = mock(SkillVersionFileMapper.class);
        ArtifactStore store = mock(ArtifactStore.class);
        SkillPackageImportServiceImpl service = new SkillPackageImportServiceImpl(
                attempts, assets, artifacts, versions, files, store, 1024 * 1024);

        ImportAttemptVO result = service.importPackage(command(zip("SKILL.md", "# Demo", "../secret.txt", "bad")), "admin");

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getFailureReason().contains("非法文件路径"));
        verify(attempts).updateResult(any(SkillImportAttemptEntity.class));
    }

    private SkillPackageImportCommand command(byte[] bytes) {
        SkillPackageImportCommand command = new SkillPackageImportCommand();
        command.setRequestId("request-1");
        command.setAssetKey("demo");
        command.setName("Demo");
        command.setDescription("Demo skill");
        command.setOwnerScopeId(1L);
        command.setVersionLabel("1.0.0");
        command.setSourceLocator("demo.zip");
        command.setFilename("demo.zip");
        command.setPackageBytes(bytes);
        return command;
    }

    private byte[] zip(String firstPath, String firstContent, String secondPath, String secondContent) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output);
        zip.putNextEntry(new ZipEntry(firstPath));
        zip.write(firstContent.getBytes("UTF-8"));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry(secondPath));
        zip.write(secondContent.getBytes("UTF-8"));
        zip.closeEntry();
        zip.close();
        return output.toByteArray();
    }
}
