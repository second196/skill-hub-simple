package com.km.skillhub.version.service.impl;

import com.km.skillhub.content.model.entity.SkillVersionFileEntity;
import com.km.skillhub.content.service.SkillVersionContentService;
import com.km.skillhub.mapper.catalog.AssetCatalogMapper;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import com.km.skillhub.version.model.vo.VersionCompareVO;
import com.km.skillhub.version.service.VersionComparisonService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VersionComparisonServiceImpl implements VersionComparisonService {
    private final SkillVersionMapper versionMapper;
    private final AssetCatalogMapper catalogMapper;
    private final SkillVersionContentService contentService;

    public VersionComparisonServiceImpl(SkillVersionMapper versionMapper, AssetCatalogMapper catalogMapper,
                                        SkillVersionContentService contentService) {
        this.versionMapper = versionMapper;
        this.catalogMapper = catalogMapper;
        this.contentService = contentService;
    }

    @Override
    public VersionCompareVO compare(String fromDigest, String toDigest, String username) {
        SkillVersionEntity from = versionMapper.findByDigest(fromDigest);
        SkillVersionEntity to = versionMapper.findByDigest(toDigest);
        if (from == null || to == null || !from.getAssetId().equals(to.getAssetId())
                || !catalogMapper.hasVersionAccess(fromDigest, username)
                || !catalogMapper.hasVersionAccess(toDigest, username)) {
            throw new org.springframework.security.access.AccessDeniedException("无权比较指定版本");
        }
        Map<String, SkillVersionFileEntity> oldFiles = byPath(contentService.listFiles(fromDigest, username));
        Map<String, SkillVersionFileEntity> newFiles = byPath(contentService.listFiles(toDigest, username));
        List<String> paths = new ArrayList<String>(oldFiles.keySet());
        for (String path : newFiles.keySet()) {
            if (!paths.contains(path)) paths.add(path);
        }
        Collections.sort(paths);
        List<VersionCompareVO.FileDifferenceVO> differences = new ArrayList<VersionCompareVO.FileDifferenceVO>();
        for (String path : paths) {
            SkillVersionFileEntity oldFile = oldFiles.get(path);
            SkillVersionFileEntity newFile = newFiles.get(path);
            String change = oldFile == null ? "ADDED" : newFile == null ? "REMOVED"
                    : same(oldFile.getContentDigest(), newFile.getContentDigest()) ? "UNCHANGED" : "MODIFIED";
            if (!"UNCHANGED".equals(change)) {
                differences.add(new VersionCompareVO.FileDifferenceVO(path, change,
                        oldFile == null ? null : oldFile.getContentDigest(),
                        newFile == null ? null : newFile.getContentDigest()));
            }
        }
        return new VersionCompareVO(fromDigest, toDigest, from.getVersionLabel(), to.getVersionLabel(), differences);
    }

    private Map<String, SkillVersionFileEntity> byPath(List<com.km.skillhub.content.model.vo.SkillFileVO> files) {
        Map<String, SkillVersionFileEntity> result = new HashMap<String, SkillVersionFileEntity>();
        for (com.km.skillhub.content.model.vo.SkillFileVO file : files) {
            SkillVersionFileEntity entity = new SkillVersionFileEntity();
            entity.setPath(file.getPath());
            entity.setContentDigest(file.getContentDigest());
            result.put(file.getPath(), entity);
        }
        return result;
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
