package com.km.skillhub.integration.runtime;

import com.km.skillhub.integration.artifact.ArtifactAccessDescriptor;

public class InstallationCommand {
    private final String eventId;
    private final String operationId;
    private final String eventType;
    private final Long instanceId;
    private final Long assetId;
    private final String versionDigest;
    private final String previousVersionDigest;
    private final String runtimeKey;
    private final String runtimeVersion;
    private final Long scopeId;
    private final ArtifactAccessDescriptor artifact;

    public InstallationCommand(String eventId, String operationId, String eventType, Long instanceId, Long assetId,
                               String versionDigest, String previousVersionDigest, String runtimeKey,
                               String runtimeVersion, Long scopeId, ArtifactAccessDescriptor artifact) {
        this.eventId = eventId; this.operationId = operationId; this.eventType = eventType; this.instanceId = instanceId;
        this.assetId = assetId; this.versionDigest = versionDigest; this.previousVersionDigest = previousVersionDigest;
        this.runtimeKey = runtimeKey; this.runtimeVersion = runtimeVersion; this.scopeId = scopeId; this.artifact = artifact;
    }

    public String getEventId() { return eventId; }
    public String getOperationId() { return operationId; }
    public String getEventType() { return eventType; }
    public Long getInstanceId() { return instanceId; }
    public Long getAssetId() { return assetId; }
    public String getVersionDigest() { return versionDigest; }
    public String getPreviousVersionDigest() { return previousVersionDigest; }
    public String getRuntimeKey() { return runtimeKey; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public Long getScopeId() { return scopeId; }
    public ArtifactAccessDescriptor getArtifact() { return artifact; }
}
