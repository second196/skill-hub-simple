package com.km.skillhub.skill;

import java.util.List;

public class SkillPackage {
    private final String name;
    private final String description;
    private final String version;
    /** Optional category declared inside the package; null when absent. */
    private final String category;
    private final List<FileEntry> files;

    public SkillPackage(String name, String description, String version, String category, List<FileEntry> files) {
        this.name = name;
        this.description = description;
        this.version = version;
        this.category = category;
        this.files = files;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public String getCategory() { return category; }
    public List<FileEntry> getFiles() { return files; }

    public static class FileEntry {
        private final String path;
        private final byte[] content;
        private final String contentType;
        private final String contentDigest;

        public FileEntry(String path, byte[] content, String contentType, String contentDigest) {
            this.path = path;
            this.content = content;
            this.contentType = contentType;
            this.contentDigest = contentDigest;
        }

        public String getPath() { return path; }
        public byte[] getContent() { return content; }
        public String getContentType() { return contentType; }
        public String getContentDigest() { return contentDigest; }
    }
}
