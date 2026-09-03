package com.km.skillhub.integration.runtime;

import com.km.skillhub.installation.mapper.InstallationOperationEventMapper;
import org.springframework.stereotype.Service;

@Service
public class InstallationEventDeduplicationService {
    private final InstallationOperationEventMapper eventMapper;

    public InstallationEventDeduplicationService(InstallationOperationEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public boolean alreadyProcessed(String eventId) {
        return eventId != null && eventMapper.findByEventId(eventId) != null;
    }
}
