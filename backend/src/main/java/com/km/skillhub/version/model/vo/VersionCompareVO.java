package com.km.skillhub.version.model.vo;

import java.util.List;

public class VersionCompareVO {
    private final String fromDigest;
    private final String toDigest;
    private final String fromVersion;
    private final String toVersion;
    private final List<FileDifferenceVO> files;

    public VersionCompareVO(String fromDigest, String toDigest, String fromVersion, String toVersion,
                            List<FileDifferenceVO> files) {
        this.fromDigest = fromDigest;
        this.toDigest = toDigest;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.files = files;
    }

    public String getFromDigest() { return fromDigest; }
    public String getToDigest() { return toDigest; }
    public String getFromVersion() { return fromVersion; }
    public String getToVersion() { return toVersion; }
    public List<FileDifferenceVO> getFiles() { return files; }

    public static class FileDifferenceVO {
        private final String path;
        private final String changeType;
        private final String fromDigest;
        private final String toDigest;

        public FileDifferenceVO(String path, String changeType, String fromDigest, String toDigest) {
            this.path = path;
            this.changeType = changeType;
            this.fromDigest = fromDigest;
            this.toDigest = toDigest;
        }

        public String getPath() { return path; }
        public String getChangeType() { return changeType; }
        public String getFromDigest() { return fromDigest; }
        public String getToDigest() { return toDigest; }
    }
}
