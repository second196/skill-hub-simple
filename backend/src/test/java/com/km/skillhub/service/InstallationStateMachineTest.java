package com.km.skillhub.service;

import com.km.skillhub.installation.domain.InstallationStateMachine;
import com.km.skillhub.installation.model.InstallationState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstallationStateMachineTest {
    @Test
    void allowsOrderedInstallationProgress() {
        assertEquals(InstallationState.DOWNLOADING,
                InstallationStateMachine.transition(InstallationState.VALIDATING, InstallationState.DOWNLOADING));
        assertEquals(InstallationState.SUCCEEDED,
                InstallationStateMachine.transition(InstallationState.VERIFYING, InstallationState.SUCCEEDED));
    }

    @Test
    void rejectsEnablingBeforeVerification() {
        assertThrows(IllegalArgumentException.class,
                () -> InstallationStateMachine.transition(InstallationState.INSTALLING_SKILL,
                        InstallationState.SUCCEEDED));
    }

    @Test
    void allowsRollbackOnlyAfterFailure() {
        assertEquals(InstallationState.ROLLING_BACK,
                InstallationStateMachine.transition(InstallationState.FAILED, InstallationState.ROLLING_BACK));
        assertThrows(IllegalArgumentException.class,
                () -> InstallationStateMachine.transition(InstallationState.SUCCEEDED,
                        InstallationState.ROLLING_BACK));
    }
}
