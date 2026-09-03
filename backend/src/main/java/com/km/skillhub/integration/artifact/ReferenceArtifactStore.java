package com.km.skillhub.integration.artifact;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Component
public class ReferenceArtifactStore implements ArtifactStore {

    @Value("${skillhub.artifact.root:}")
    private String configuredRoot;

    @Override
    public ArtifactLocation store(String content, String artifactDigest) {
        if (content == null) {
            throw new IllegalArgumentException("Artifact content is required");
        }
        return store(content.getBytes(StandardCharsets.UTF_8), artifactDigest, "text/plain");
    }

    @Override
    public ArtifactLocation store(byte[] content, String artifactDigest, String mediaType) {
        if (content == null || artifactDigest == null || !artifactDigest.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Artifact content and digest are required");
        }
        try {
            Path path = artifactPath(artifactDigest);
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.write(path, content, StandardOpenOption.CREATE_NEW);
            }
            return new ArtifactLocation("local://" + artifactDigest, content.length,
                    mediaType == null || mediaType.trim().isEmpty() ? "application/octet-stream" : mediaType);
        } catch (IOException exception) {
            throw new IllegalStateException("Artifact storage failed", exception);
        }
    }

    @Override
    public InputStream open(String uri) throws IOException {
        if (uri == null || !uri.startsWith("local://")) {
            throw new IllegalArgumentException("Only local artifact references are supported");
        }
        String digest = uri.substring("local://".length());
        if (!digest.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Artifact reference is invalid");
        }
        return Files.newInputStream(artifactPath(digest), StandardOpenOption.READ);
    }

    private Path artifactPath(String artifactDigest) {
        String root = configuredRoot;
        if (root == null || root.trim().isEmpty()) {
            root = System.getProperty("skillhub.artifact.root");
        }
        if (root == null || root.trim().isEmpty()) {
            root = System.getenv("SKILLHUB_ARTIFACT_ROOT");
        }
        if (root == null || root.trim().isEmpty()) {
            root = Paths.get(System.getProperty("java.io.tmpdir"), "skillhub-artifacts").toString();
        }
        return Paths.get(root).toAbsolutePath().normalize().resolve(artifactDigest.substring(0, 2)).resolve(artifactDigest);
    }
}
