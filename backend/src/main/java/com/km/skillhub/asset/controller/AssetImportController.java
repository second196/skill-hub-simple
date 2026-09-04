package com.km.skillhub.asset.controller;

import com.km.skillhub.asset.model.dto.AssetImportRequest;
import com.km.skillhub.asset.model.dto.SkillPackageImportCommand;
import com.km.skillhub.asset.model.vo.ImportAttemptVO;
import com.km.skillhub.asset.model.vo.SkillPackageValidationVO;
import com.km.skillhub.asset.service.AssetImportService;
import com.km.skillhub.asset.service.SkillPackageImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/assets/imports")
public class AssetImportController {

    private final AssetImportService assetImportService;
    private final SkillPackageImportService skillPackageImportService;

    public AssetImportController(AssetImportService assetImportService,
                                 SkillPackageImportService skillPackageImportService) {
        this.assetImportService = assetImportService;
        this.skillPackageImportService = skillPackageImportService;
    }

    @PostMapping
    public ResponseEntity<ImportAttemptVO> importAsset(@RequestBody AssetImportRequest request,
                                                       Authentication authentication) {
        return ResponseEntity.ok(assetImportService.importAsset(request, authentication.getName()));
    }

    @PostMapping(value = "/package", consumes = "multipart/form-data")
    public ResponseEntity<ImportAttemptVO> importPackage(
            @RequestHeader(value = "X-Request-Id", required = false) String requestIdHeader,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String assetKey,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam Long ownerScopeId,
            @RequestParam(required = false) String versionLabel,
            @RequestParam(required = false) String sourceLocator,
            @RequestParam(required = false) String artifactDigest,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) throws java.io.IOException {
        requirePackage(file);
        SkillPackageImportCommand command = new SkillPackageImportCommand();
        command.setRequestId(firstNonBlank(requestIdHeader, requestId));
        command.setAssetKey(assetKey);
        command.setName(name);
        command.setDescription(description);
        command.setOwnerScopeId(ownerScopeId);
        command.setVersionLabel(versionLabel);
        command.setFilename(file.getOriginalFilename());
        command.setSourceLocator(sourceLocator == null || sourceLocator.trim().isEmpty()
                ? file.getOriginalFilename() : sourceLocator);
        command.setPackageBytes(file.getBytes());
        command.setClientArtifactDigest(artifactDigest);
        return ResponseEntity.ok(skillPackageImportService.importPackage(command, authentication.getName()));
    }

    @PostMapping(value = "/package/validate", consumes = "multipart/form-data")
    public ResponseEntity<SkillPackageValidationVO> validatePackage(
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        requirePackage(file);
        return ResponseEntity.ok(skillPackageImportService.validatePackage(file.getBytes()));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ImportAttemptVO> getImport(@PathVariable String requestId) {
        return ResponseEntity.ok(assetImportService.getImportAttempt(requestId));
    }

    private void requirePackage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请上传非空 Skill 包");
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.trim().isEmpty() ? fallback : preferred;
    }
}
