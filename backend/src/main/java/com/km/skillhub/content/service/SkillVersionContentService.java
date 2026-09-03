package com.km.skillhub.content.service;

import com.km.skillhub.content.model.vo.SkillFileVO;

import java.io.InputStream;
import java.util.List;

public interface SkillVersionContentService {
    List<SkillFileVO> listFiles(String versionDigest, String username);

    InputStream openFile(String versionDigest, String path, String username);

    InputStream openPackage(String versionDigest, String username);
}
