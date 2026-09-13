package com.km.skillhub.discovery;

public class DiscoveredFileRecord {
    private String path;
    private String sourceUrl;
    private String contentType;
    private long sizeBytes;
    private boolean binary;

    public DiscoveredFileRecord() {}

    public DiscoveredFileRecord(String path, String sourceUrl, String contentType, long sizeBytes, boolean binary) {
        this.path = path;
        this.sourceUrl = sourceUrl;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.binary = binary;
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public boolean isBinary() { return binary; }
    public void setBinary(boolean binary) { this.binary = binary; }
}
