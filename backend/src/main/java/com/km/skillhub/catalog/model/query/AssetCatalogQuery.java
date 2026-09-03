package com.km.skillhub.catalog.model.query;

public class AssetCatalogQuery {
    private String keyword;
    private String lifecycleState;
    private long page = 1;
    private long pageSize = 20;

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public String getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(String lifecycleState) { this.lifecycleState = lifecycleState; }
    public long getPage() { return page; }
    public void setPage(long page) { this.page = page; }
    public long getPageSize() { return pageSize; }
    public void setPageSize(long pageSize) { this.pageSize = pageSize; }

    public long offset() {
        return Math.max(0, page - 1) * Math.min(Math.max(pageSize, 1), 100);
    }

    public long limit() {
        return Math.min(Math.max(pageSize, 1), 100);
    }
}
