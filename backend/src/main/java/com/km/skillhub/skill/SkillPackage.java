package com.km.skillhub.skill;

import java.util.List;

public class SkillPackage {
    private final String name;
    private final String description;
    private final String version;
    private final String digest;
    private final List<FileEntry> files;

    public SkillPackage(String name, String description, String version, String digest, List<FileEntry> files) {
        this.name = name;
        this.description = description;
        this.version = version;
        this.digest = digest;
        this.files = files;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public String getDigest() { return digest; }
    public List<FileEntry> getFiles() { return files; }

    public static class FileEntry {
        private final String path;
        private final byte[] content;
        private final String contentType;
        private final String digest;

        public FileEntry(String path, byte[] content, String contentType, String digest) {
            this.path = path;
            this.content = content;
            this.contentType = contentType;
            this.digest = digest;
        }

        public String getPath() { return path; }
        public byte[] getContent() { return content; }
        public String getContentType() { return contentType; }
        public String getDigest() { return digest; }
    }
}
