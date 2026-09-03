package com.km.skillhub.service;

import com.km.skillhub.integration.runtime.InstallationEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class InstallationEventContractTest {
    @Test
    void rejectsEventsWithoutConcreteVersionDigest() {
        InstallationEvent event = new InstallationEvent("event-1", "operation-1", 1, "FAILED", 1L, 1L,
                "latest", null, "codex-cli", "initial", 1L, "DOWNLOADING", "DOWNLOAD_FAILED", "failed");
        assertThrows(IllegalArgumentException.class, event::validate);
    }
}
