package com.km.skillhub.integration.runtime;

import com.km.skillhub.integration.event.GovernanceEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class InstallationObservabilityEventPublisher {
    private final GovernanceEventPublisher eventPublisher;

    public InstallationObservabilityEventPublisher(GovernanceEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public String publish(InstallationEvent event) {
        if (event == null) throw new IllegalArgumentException("Installation event is required");
        event.validate();
        return eventPublisher.publish("INSTALLATION_" + event.getEventType(), "installation_operation",
                event.getOperationId(), event);
    }
}
