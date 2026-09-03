package com.km.skillhub.installation.model;

public enum InstallationState {
    REQUESTED,
    VALIDATING,
    DOWNLOADING,
    INSTALLING_SKILL,
    INSTALLING_TRACKER,
    CONFIGURING,
    VERIFYING,
    SWITCHING,
    SUCCEEDED,
    INCOMPLETE,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK,
    REQUIRES_MANUAL,
    REVOKE_REQUESTED,
    REVOKING,
    REVOKED,
    PARTIAL_REVOKED,
    UNAVAILABLE;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == INCOMPLETE || this == FAILED || this == ROLLED_BACK
                || this == REQUIRES_MANUAL || this == REVOKED || this == PARTIAL_REVOKED
                || this == UNAVAILABLE;
    }
}
