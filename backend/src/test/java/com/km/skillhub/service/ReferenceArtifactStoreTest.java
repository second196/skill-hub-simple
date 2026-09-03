package com.km.skillhub.service;

import com.km.skillhub.integration.artifact.ArtifactStore;
import com.km.skillhub.integration.artifact.ReferenceArtifactStore;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ReferenceArtifactStoreTest {
    @Test
    void storesAndReadsLocalArtifactByDigest() throws Exception {
        byte[] content = "skill-content".getBytes(StandardCharsets.UTF_8);
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(content));
        ArtifactStore store = new ReferenceArtifactStore();
        ArtifactStore.ArtifactLocation location = store.store(content, digest, "text/plain");
        InputStream input = store.open(location.getUri());
        byte[] loaded = new byte[content.length];
        int read = input.read(loaded);
        input.close();
        assertArrayEquals(content, java.util.Arrays.copyOf(loaded, read));
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
