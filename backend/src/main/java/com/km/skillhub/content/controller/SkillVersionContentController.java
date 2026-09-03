package com.km.skillhub.content.controller;

import com.km.skillhub.content.model.vo.SkillFileVO;
import com.km.skillhub.content.service.SkillVersionContentService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.List;

@RestController
@RequestMapping("/api/v1/assets/versions")
public class SkillVersionContentController {

    private final SkillVersionContentService contentService;

    public SkillVersionContentController(SkillVersionContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping("/{versionDigest}/files")
    public ResponseEntity<List<SkillFileVO>> listFiles(@PathVariable String versionDigest,
                                                       Authentication authentication) {
        return ResponseEntity.ok(contentService.listFiles(versionDigest, authentication.getName()));
    }

    @GetMapping("/{versionDigest}/file")
    public ResponseEntity<InputStreamResource> openFile(@PathVariable String versionDigest,
                                                        @RequestParam String path,
                                                        Authentication authentication) {
        InputStream input = contentService.openFile(versionDigest, path, authentication.getName());
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(new InputStreamResource(input));
    }

    @GetMapping("/{versionDigest}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String versionDigest,
                                                        Authentication authentication) {
        InputStream input = contentService.openPackage(versionDigest, authentication.getName());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=skill-" + versionDigest + ".zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(input));
    }
}
