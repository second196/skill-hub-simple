package com.km.skillhub.installation.model.vo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RevocationResultVO {
    private final String versionDigest;
    private final int total;
    private final int queued;
    private final List<String> operationIds;

    public RevocationResultVO(String versionDigest, int total, int queued, List<String> operationIds) {
        this.versionDigest = versionDigest;
        this.total = total;
        this.queued = queued;
        this.operationIds = operationIds == null
                ? Collections.<String>emptyList() : new ArrayList<String>(operationIds);
    }

    public String getVersionDigest() { return versionDigest; }
    public int getTotal() { return total; }
    public int getQueued() { return queued; }
    public List<String> getOperationIds() { return operationIds; }
}
