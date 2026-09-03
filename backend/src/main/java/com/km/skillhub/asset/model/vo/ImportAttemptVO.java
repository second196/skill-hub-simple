package com.km.skillhub.asset.model.vo;

public class ImportAttemptVO {
    private final String requestId;
    private final String status;
    private final Long assetId;
    private final String versionDigest;
    private final String failureStage;
    private final String failureCode;
    private final String failureReason;

    public ImportAttemptVO(String requestId, String status, Long assetId, String versionDigest,
                           String failureStage, String failureCode, String failureReason) {
        this.requestId = requestId;
        this.status = status;
        this.assetId = assetId;
        this.versionDigest = versionDigest;
        this.failureStage = failureStage;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
    }

    public String getRequestId() { return requestId; }
    public String getStatus() { return status; }
    public Long getAssetId() { return assetId; }
    public String getVersionDigest() { return versionDigest; }
    public String getFailureStage() { return failureStage; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
}
