package com.km.skillhub.service;

import com.km.skillhub.installation.model.FailureStage;
import com.km.skillhub.installation.model.InstallationState;
import com.km.skillhub.installation.model.OperationType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallationPersistenceContractTest {
    @Test
    void exposesTheInstallationOperationVocabulary() {
        assertTrue(InstallationState.REQUESTED.isTerminal() == false);
        assertTrue(InstallationState.REQUIRES_MANUAL.isTerminal());
        assertTrue(OperationType.values().length == 4);
        assertTrue(FailureStage.valueOf("ROLLING_BACK") == FailureStage.ROLLING_BACK);
    }
}
