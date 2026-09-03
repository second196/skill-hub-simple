package com.km.skillhub.installation.model;

public enum FailureStage {
    VALIDATING,
    DOWNLOADING,
    INSTALLING_SKILL,
    INSTALLING_TRACKER,
    CONFIGURING,
    VERIFYING,
    DISABLING_OLD_VERSION,
    ENABLING_NEW_VERSION,
    ROLLING_BACK,
    REVOKING,
    REPORTING
}
