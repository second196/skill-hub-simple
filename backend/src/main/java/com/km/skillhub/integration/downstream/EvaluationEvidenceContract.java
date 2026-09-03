package com.km.skillhub.integration.downstream;

public final class EvaluationEvidenceContract {
    private EvaluationEvidenceContract() { }
    public static void validate(String versionDigest) {
        RuntimeEvidenceContract.validate(versionDigest);
    }
}
