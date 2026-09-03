package com.km.skillhub.integration.artifact;

import org.springframework.stereotype.Component;

@Component
public class ArtifactAccessResolver {
    public ArtifactAccessDescriptor describe(String uri, String digest) {
        if (uri == null || uri.trim().isEmpty() || digest == null || !digest.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Artifact access descriptor is incomplete");
        }
        return new ArtifactAccessDescriptor(uri, digest);
    }
}
