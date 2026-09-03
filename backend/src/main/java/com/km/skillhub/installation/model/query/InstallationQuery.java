package com.km.skillhub.installation.model.query;

public class InstallationQuery {
    private Long scopeId;
    private Long assetId;
    private String runtimeKey;
    private int page = 1;
    private int pageSize = 20;

    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long value) { scopeId = value; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long value) { assetId = value; }
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    public int getPage() { return page; }
    public void setPage(int value) { page = value; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int value) { pageSize = value; }
    public int limit() { return Math.min(Math.max(pageSize, 1), 100); }
    public int offset() { return (Math.max(page, 1) - 1) * limit(); }
}
