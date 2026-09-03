package com.km.skillhub.version.service.impl;

import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.version.mapper.SkillVersionTagMapper;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import com.km.skillhub.version.model.entity.SkillVersionTagEntity;
import com.km.skillhub.version.service.VersionTagService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
public class VersionTagServiceImpl implements VersionTagService {
    private static final Pattern TAG = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern SEMVER = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?");
    private final SkillVersionMapper versionMapper;
    private final SkillVersionTagMapper tagMapper;

    public VersionTagServiceImpl(SkillVersionMapper versionMapper, SkillVersionTagMapper tagMapper) {
        this.versionMapper = versionMapper;
        this.tagMapper = tagMapper;
    }

    @Override
    @Transactional
    public void bind(String versionDigest, String tagName, String username) {
        if (versionDigest == null || tagName == null || username == null || !TAG.matcher(tagName).matches()) {
            throw new IllegalArgumentException("标签格式不合法");
        }
        SkillVersionEntity version = versionMapper.findByDigest(versionDigest);
        if (version == null || !SEMVER.matcher(version.getVersionLabel()).matches()
                || !("CANDIDATE".equals(version.getLifecycleState()) || "PUBLISHED".equals(version.getLifecycleState()))) {
            throw new IllegalArgumentException("只有可分发的语义化版本可以绑定标签");
        }
        SkillVersionTagEntity current = tagMapper.find(version.getAssetId(), tagName);
        String previous = current == null ? null : current.getVersionDigest();
        if (current == null) {
            current = new SkillVersionTagEntity();
            current.setAssetId(version.getAssetId());
            current.setTagName(tagName);
            current.setVersionDigest(versionDigest);
            current.setCreatedBy(username);
            tagMapper.insert(current);
        } else {
            current.setVersionDigest(versionDigest);
            current.setCreatedBy(username);
            tagMapper.update(current);
        }
        tagMapper.insertHistory(version.getAssetId(), tagName, previous, versionDigest, username);
    }
}
