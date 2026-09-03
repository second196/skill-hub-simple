package com.km.skillhub.integration.artifact;

public class ArtifactAccessDescriptor {
    private final String uri;
    private final String digest;

    public ArtifactAccessDescriptor(String uri, String digest) {
        this.uri = uri;
        this.digest = digest;
    }

    public String getUri() { return uri; }
    public String getDigest() { return digest; }
}
