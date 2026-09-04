package com.km.skillhub.asset.model.vo;

public class ImportAttemptVO {
    private final String requestId;
    private final String status;
    private final Long assetId;
    private final String artifactDigest;
    private final String versionDigest;
    private final String lifecycleState;
    private final boolean duplicate;
    private final String consolePath;
    private final SkillPackageValidationVO validation;
    private final String failureStage;
    private final String failureCode;
    private final String failureReason;

    public ImportAttemptVO(String requestId, String status, Long assetId, String versionDigest,
                           String failureStage, String failureCode, String failureReason) {
        this.requestId = requestId;
        this.status = status;
        this.assetId = assetId;
        this.artifactDigest = versionDigest;
        this.versionDigest = versionDigest;
        this.lifecycleState = null;
        this.duplicate = false;
        this.consolePath = null;
        this.validation = null;
        this.failureStage = failureStage;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
    }

    public String getRequestId() { return requestId; }
    public String getStatus() { return status; }
    public Long getAssetId() { return assetId; }
    public String getArtifactDigest() { return artifactDigest; }
    public String getVersionDigest() { return versionDigest; }
    public String getLifecycleState() { return lifecycleState; }
    public boolean isDuplicate() { return duplicate; }
    public String getConsolePath() { return consolePath; }
    public SkillPackageValidationVO getValidation() { return validation; }
    public String getFailureStage() { return failureStage; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }

    public ImportAttemptVO(String requestId, String status, Long assetId, String artifactDigest,
                           String versionDigest, String lifecycleState, boolean duplicate,
                           SkillPackageValidationVO validation, String failureStage,
                           String failureCode, String failureReason) {
        this.requestId = requestId;
        this.status = status;
        this.assetId = assetId;
        this.artifactDigest = artifactDigest;
        this.versionDigest = versionDigest;
        this.lifecycleState = lifecycleState;
        this.duplicate = duplicate;
        this.validation = validation;
        this.consolePath = assetId == null || versionDigest == null
                ? null : "/assets/" + assetId + "/versions/" + versionDigest;
        this.failureStage = failureStage;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
    }
}
