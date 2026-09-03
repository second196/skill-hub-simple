package com.km.skillhub.version.service;

import com.km.skillhub.version.model.vo.VersionCompareVO;

public interface VersionComparisonService {
    VersionCompareVO compare(String fromDigest, String toDigest, String username);
}
