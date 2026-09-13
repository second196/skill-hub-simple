package com.km.skillhub.discovery;

import java.time.OffsetDateTime;

public class DiscoveredSkillRecord {
    private String sourceType;
    private String sourceOwner;
    private String sourceRepository;
    private String sourceBranch;
    private String sourcePath;
    private String sourceUrl;
    private String installUrl;
    private String packageType;
    private String name;
    private String description;
    private String category;
    private String supportedAgents;
    private long githubStars;
    private long githubForks;
    private long externalInstallCount;
    private double trendScore;
    private double qualityScore;
    private String trustLevel;
    private String license;
    private OffsetDateTime sourceUpdatedAt;

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceOwner() { return sourceOwner; }
    public void setSourceOwner(String sourceOwner) { this.sourceOwner = sourceOwner; }
    public String getSourceRepository() { return sourceRepository; }
    public void setSourceRepository(String sourceRepository) { this.sourceRepository = sourceRepository; }
    public String getSourceBranch() { return sourceBranch; }
    public void setSourceBranch(String sourceBranch) { this.sourceBranch = sourceBranch; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getInstallUrl() { return installUrl; }
    public void setInstallUrl(String installUrl) { this.installUrl = installUrl; }
    public String getPackageType() { return packageType; }
    public void setPackageType(String packageType) { this.packageType = packageType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSupportedAgents() { return supportedAgents; }
    public void setSupportedAgents(String supportedAgents) { this.supportedAgents = supportedAgents; }
    public long getGithubStars() { return githubStars; }
    public void setGithubStars(long githubStars) { this.githubStars = githubStars; }
    public long getGithubForks() { return githubForks; }
    public void setGithubForks(long githubForks) { this.githubForks = githubForks; }
    public long getExternalInstallCount() { return externalInstallCount; }
    public void setExternalInstallCount(long externalInstallCount) { this.externalInstallCount = externalInstallCount; }
    public double getTrendScore() { return trendScore; }
    public void setTrendScore(double trendScore) { this.trendScore = trendScore; }
    public double getQualityScore() { return qualityScore; }
    public void setQualityScore(double qualityScore) { this.qualityScore = qualityScore; }
    public String getTrustLevel() { return trustLevel; }
    public void setTrustLevel(String trustLevel) { this.trustLevel = trustLevel; }
    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }
    public OffsetDateTime getSourceUpdatedAt() { return sourceUpdatedAt; }
    public void setSourceUpdatedAt(OffsetDateTime sourceUpdatedAt) { this.sourceUpdatedAt = sourceUpdatedAt; }
}
