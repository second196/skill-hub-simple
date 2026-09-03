package com.km.skillhub.integration.event;

import com.km.skillhub.integration.runtime.InstallationCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class InstallationCommandPublisher {
    private static final String DEFAULT_STREAM_KEY = "skillhub:installation:commands";
    private final GovernanceEventPublisher eventPublisher;
    private final String streamKey;

    @Autowired
    public InstallationCommandPublisher(GovernanceEventPublisher eventPublisher,
                                        @Value("${skillhub.installation.command-stream:skillhub:installation:commands}") String streamKey) {
        this.eventPublisher = eventPublisher;
        this.streamKey = streamKey == null || streamKey.trim().isEmpty() ? DEFAULT_STREAM_KEY : streamKey;
    }

    public InstallationCommandPublisher(GovernanceEventPublisher eventPublisher) {
        this(eventPublisher, DEFAULT_STREAM_KEY);
    }

    public String publish(InstallationCommand command) {
        if (command == null || command.getEventId() == null || command.getOperationId() == null || command.getVersionDigest() == null
                || !command.getVersionDigest().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Installation command is incomplete");
        }
        return eventPublisher.publishToStream(command.getEventId(), streamKey, command.getEventType(),
                "installation_operation", command.getOperationId(), command);
    }
}
