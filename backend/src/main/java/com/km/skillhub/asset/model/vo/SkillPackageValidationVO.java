package com.km.skillhub.asset.model.vo;

import java.util.Collections;
import java.util.List;

public class SkillPackageValidationVO {
    private final boolean valid;
    private final String name;
    private final String description;
    private final String versionLabel;
    private final String artifactDigest;
    private final String versionDigest;
    private final long expandedSizeBytes;
    private final List<ManifestEntry> manifest;

    public SkillPackageValidationVO(String name, String description, String versionLabel,
                                    String artifactDigest, String versionDigest,
                                    long expandedSizeBytes, List<ManifestEntry> manifest) {
        this.valid = true;
        this.name = name;
        this.description = description;
        this.versionLabel = versionLabel;
        this.artifactDigest = artifactDigest;
        this.versionDigest = versionDigest;
        this.expandedSizeBytes = expandedSizeBytes;
        this.manifest = Collections.unmodifiableList(manifest);
    }

    public boolean isValid() { return valid; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersionLabel() { return versionLabel; }
    public String getArtifactDigest() { return artifactDigest; }
    public String getVersionDigest() { return versionDigest; }
    public long getExpandedSizeBytes() { return expandedSizeBytes; }
    public List<ManifestEntry> getManifest() { return manifest; }

    public static class ManifestEntry {
        private final String path;
        private final long sizeBytes;
        private final String contentDigest;

        public ManifestEntry(String path, long sizeBytes, String contentDigest) {
            this.path = path;
            this.sizeBytes = sizeBytes;
            this.contentDigest = contentDigest;
        }

        public String getPath() { return path; }
        public long getSizeBytes() { return sizeBytes; }
        public String getContentDigest() { return contentDigest; }
    }
}
