package com.km.skillhub.integration.runtime;

import com.km.skillhub.installation.service.InstallationProgressService;
import org.springframework.stereotype.Service;

@Service
public class InstallationEventConsumer {
    private final InstallationProgressService progressService;
    private final InstallationEventDeduplicationService deduplicationService;

    public InstallationEventConsumer(InstallationProgressService progressService,
                                      InstallationEventDeduplicationService deduplicationService) {
        this.progressService = progressService;
        this.deduplicationService = deduplicationService;
    }

    public boolean consume(InstallationEvent event) {
        if (event == null || event.getEventId() == null) {
            throw new IllegalArgumentException("Installation event is required");
        }
        if (deduplicationService.alreadyProcessed(event.getEventId())) return false;
        progressService.accept(event, "runtime-adapter");
        return true;
    }
}
