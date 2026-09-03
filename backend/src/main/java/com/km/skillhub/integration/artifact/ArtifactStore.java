package com.km.skillhub.integration.artifact;

import java.io.IOException;
import java.io.InputStream;

public interface ArtifactStore {

    ArtifactLocation store(String content, String artifactDigest);

    ArtifactLocation store(byte[] content, String artifactDigest, String mediaType);

    InputStream open(String uri) throws IOException;

    class ArtifactLocation {
        private final String uri;
        private final long sizeBytes;
        private final String mediaType;

        public ArtifactLocation(String uri, long sizeBytes, String mediaType) {
            this.uri = uri;
            this.sizeBytes = sizeBytes;
            this.mediaType = mediaType;
        }

        public String getUri() { return uri; }
        public long getSizeBytes() { return sizeBytes; }
        public String getMediaType() { return mediaType; }
    }
}
