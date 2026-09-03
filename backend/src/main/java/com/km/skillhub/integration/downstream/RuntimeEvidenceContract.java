package com.km.skillhub.integration.downstream;

public final class RuntimeEvidenceContract {
    private RuntimeEvidenceContract() { }
    public static void validate(String versionDigest) {
        if (versionDigest == null || !versionDigest.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Runtime evidence requires a SHA-256 version digest");
        }
    }
}
