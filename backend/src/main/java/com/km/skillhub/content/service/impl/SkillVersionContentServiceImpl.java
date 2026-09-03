package com.km.skillhub.content.service.impl;

import com.km.skillhub.asset.model.entity.SkillArtifactEntity;
import com.km.skillhub.content.model.entity.SkillVersionFileEntity;
import com.km.skillhub.content.model.vo.SkillFileVO;
import com.km.skillhub.content.service.SkillVersionContentService;
import com.km.skillhub.integration.artifact.ArtifactStore;
import com.km.skillhub.mapper.asset.SkillArtifactMapper;
import com.km.skillhub.mapper.catalog.AssetCatalogMapper;
import com.km.skillhub.mapper.content.SkillVersionFileMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SkillVersionContentServiceImpl implements SkillVersionContentService {

    private final AssetCatalogMapper catalogMapper;
    private final SkillVersionFileMapper fileMapper;
    private final SkillArtifactMapper artifactMapper;
    private final ArtifactStore artifactStore;

    public SkillVersionContentServiceImpl(AssetCatalogMapper catalogMapper,
                                          SkillVersionFileMapper fileMapper,
                                          SkillArtifactMapper artifactMapper,
                                          ArtifactStore artifactStore) {
        this.catalogMapper = catalogMapper;
        this.fileMapper = fileMapper;
        this.artifactMapper = artifactMapper;
        this.artifactStore = artifactStore;
    }

    @Override
    public List<SkillFileVO> listFiles(String versionDigest, String username) {
        requireAccess(versionDigest, username);
        List<SkillVersionFileEntity> files = fileMapper.findByVersionDigest(versionDigest);
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }
        List<SkillFileVO> result = new ArrayList<SkillFileVO>(files.size());
        for (SkillVersionFileEntity file : files) {
            result.add(new SkillFileVO(file.getPath(), file.isRequired(), file.getReadStatus(),
                    file.getContentDigest()));
        }
        return result;
    }

    @Override
    public InputStream openFile(String versionDigest, String path, String username) {
        requireAccess(versionDigest, username);
        String safePath = validatePath(path);
        SkillArtifactEntity artifact = artifactMapper.findByVersionDigest(versionDigest);
        if (artifact == null) {
            throw new IllegalArgumentException("制品不存在");
        }
        try {
            if ("text/plain".equalsIgnoreCase(artifact.getMediaType())) {
                if (!safePath.equals(artifact.getSourceSnapshotUri())) {
                    throw new IllegalArgumentException("文件不存在");
                }
                return artifactStore.open(artifact.getArtifactUri());
            }
            ZipInputStream input = new ZipInputStream(artifactStore.open(artifact.getArtifactUri()));
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (safePath.equals(entry.getName())) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    input.close();
                    return new ByteArrayInputStream(output.toByteArray());
                }
            }
            input.close();
            throw new IllegalArgumentException("文件不存在");
        } catch (IOException exception) {
            throw new IllegalStateException("制品读取失败", exception);
        }
    }

    @Override
    public InputStream openPackage(String versionDigest, String username) {
        requireAccess(versionDigest, username);
        SkillArtifactEntity artifact = artifactMapper.findByVersionDigest(versionDigest);
        if (artifact == null) {
            throw new IllegalArgumentException("制品不存在");
        }
        try {
            return artifactStore.open(artifact.getArtifactUri());
        } catch (IOException exception) {
            throw new IllegalStateException("制品读取失败", exception);
        }
    }

    private void requireAccess(String versionDigest, String username) {
        if (versionDigest == null || !versionDigest.matches("[0-9a-fA-F]{64}")
                || username == null || username.trim().isEmpty()
                || !catalogMapper.hasVersionAccess(versionDigest, username)) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该 Skill 版本");
        }
    }

    private String validatePath(String path) {
        if (path == null || path.trim().isEmpty() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("..") || path.contains("\\")) {
            throw new IllegalArgumentException("文件路径不合法");
        }
        return path;
    }
}
