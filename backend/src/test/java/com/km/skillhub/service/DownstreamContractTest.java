package com.km.skillhub.service;

import com.km.skillhub.integration.downstream.EvaluationEvidenceContract;
import com.km.skillhub.integration.downstream.RuntimeEvidenceContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DownstreamContractTest {
    @Test
    void rejectsUnboundVersionReferences() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeEvidenceContract.validate("latest"));
        assertThrows(IllegalArgumentException.class, () -> EvaluationEvidenceContract.validate(""));
    }
}
