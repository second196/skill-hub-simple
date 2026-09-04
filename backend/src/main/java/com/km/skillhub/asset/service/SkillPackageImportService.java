package com.km.skillhub.asset.service;

import com.km.skillhub.asset.model.dto.SkillPackageImportCommand;
import com.km.skillhub.asset.model.vo.ImportAttemptVO;
import com.km.skillhub.asset.model.vo.SkillPackageValidationVO;

public interface SkillPackageImportService {
    SkillPackageValidationVO validatePackage(byte[] packageBytes);

    ImportAttemptVO importPackage(SkillPackageImportCommand command, String actor);
}
