package com.km.skillhub.skill;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
    private final SkillRepository repository;
    private final SkillPackageParser parser;

    public SkillController(SkillRepository repository, SkillPackageParser parser) {
        this.repository = repository;
        this.parser = parser;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String query,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "false") boolean includeOffline) {
        return repository.list(query, category, status, includeOffline);
    }

    @GetMapping("/categories")
    public List<String> categories() { return repository.categories(); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam MultipartFile file,
                                      @RequestParam(required = false) String category) throws java.io.IOException {
        // Category priority is resolved in repository: package > existing platform > this request > 其他.
        // Do not send version/digest form fields — version is parsed from the package content only.
        String requestCategory = category == null ? null : category.trim();
        if (requestCategory != null && requestCategory.length() > 128) {
            throw new IllegalArgumentException("分类不能超过 128 字符");
        }
        return repository.save(parser.parse(file.getOriginalFilename(), file.getBytes()), requestCategory);
    }

    @GetMapping("/{slug}")
    public Map<String, Object> detail(@PathVariable String slug) { return repository.detail(slug); }

    @GetMapping("/{slug}/files")
    public List<Map<String, Object>> files(@PathVariable String slug, @RequestParam(required = false) String version) {
        return repository.files(slug, version);
    }

    @GetMapping("/{slug}/files/content")
    public ResponseEntity<byte[]> file(@PathVariable String slug, @RequestParam String path,
                                       @RequestParam(required = false) String version) {
        Map<String, Object> file = repository.file(slug, version, path);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(String.valueOf(file.get("content_type"))))
                .body((byte[]) file.get("content"));
    }

    @GetMapping("/{slug}/download")
    public ResponseEntity<byte[]> download(@PathVariable String slug, @RequestParam(required = false) String version) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map<String, Object> item : repository.files(slug, version)) {
                String path = String.valueOf(item.get("path"));
                zip.putNextEntry(new ZipEntry(path));
                zip.write((byte[]) repository.file(slug, version, path).get("content"));
                zip.closeEntry();
            }
        }
        repository.incrementDownloadCount(slug);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + slug + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip")).body(output.toByteArray());
    }

    @PostMapping("/{slug}/offline")
    public ResponseEntity<Void> offline(@PathVariable String slug) {
        repository.offline(slug);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{slug}")
    public ResponseEntity<Void> delete(@PathVariable String slug) {
        repository.delete(slug);
        return ResponseEntity.noContent().build();
    }
}
