package com.km.skillhub.integration.downstream;

import com.km.skillhub.integration.event.GovernanceEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class InstallationReleaseEventPublisher {
    private final GovernanceEventPublisher eventPublisher;
    public InstallationReleaseEventPublisher(GovernanceEventPublisher eventPublisher) { this.eventPublisher = eventPublisher; }
    public String publishApproved(ReleaseEvent event) {
        if (event == null || event.getVersionDigest() == null || event.getVersionDigest().trim().isEmpty()) {
            throw new IllegalArgumentException("Release event must contain version digest");
        }
        return eventPublisher.publish(event.getEventType(), "release_decision",
                String.valueOf(event.getDecisionId()), event);
    }
}
