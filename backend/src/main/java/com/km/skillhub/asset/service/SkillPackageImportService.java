package com.km.skillhub.asset.service;

import com.km.skillhub.asset.model.dto.SkillPackageImportCommand;
import com.km.skillhub.asset.model.vo.ImportAttemptVO;

public interface SkillPackageImportService {
    ImportAttemptVO importPackage(SkillPackageImportCommand command, String actor);
}
