package com.km.skillhub.asset.service;

import com.km.skillhub.asset.model.dto.AssetImportRequest;
import com.km.skillhub.asset.model.vo.ImportAttemptVO;

public interface AssetImportService {

    ImportAttemptVO importAsset(AssetImportRequest request, String actor);

    ImportAttemptVO getImportAttempt(String requestId);
}
