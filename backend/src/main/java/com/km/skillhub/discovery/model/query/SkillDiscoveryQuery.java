package com.km.skillhub.discovery.model.query;

public class SkillDiscoveryQuery {
    private String keyword;
    private String namespaceKey;
    private String lifecycleState;
    private String tag;
    private long page = 1;
    private long pageSize = 20;

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public String getNamespaceKey() { return namespaceKey; }
    public void setNamespaceKey(String namespaceKey) { this.namespaceKey = namespaceKey; }
    public String getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(String lifecycleState) { this.lifecycleState = lifecycleState; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public long getPage() { return page; }
    public void setPage(long page) { this.page = page; }
    public long getPageSize() { return pageSize; }
    public void setPageSize(long pageSize) { this.pageSize = pageSize; }
    public long limit() { return Math.min(Math.max(pageSize, 1), 100); }
    public long offset() { return Math.max(0, page - 1) * limit(); }
}
