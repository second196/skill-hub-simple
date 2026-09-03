package com.km.skillhub.catalog.model.vo;

import java.util.List;

public class PageResult<T> {
    private final List<T> items;
    private final long page;
    private final long pageSize;

    public PageResult(List<T> items, long page, long pageSize) {
        this.items = items;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<T> getItems() { return items; }
    public long getPage() { return page; }
    public long getPageSize() { return pageSize; }
}
