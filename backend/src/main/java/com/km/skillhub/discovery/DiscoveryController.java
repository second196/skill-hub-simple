package com.km.skillhub.discovery;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {
    private final DiscoveredSkillRepository repository;
    private final DiscoverySyncService syncService;

    public DiscoveryController(DiscoveredSkillRepository repository, DiscoverySyncService syncService) {
        this.repository = repository;
        this.syncService = syncService;
    }

    @GetMapping("/skills")
    public Map<String, Object> list(@RequestParam(required = false) String query,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String source,
                                     @RequestParam(defaultValue = "trending") String sort,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "24") int pageSize) {
        return repository.list(query, category, source, sort, page, pageSize);
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return repository.categories();
    }

    @GetMapping("/skills/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return repository.detail(id);
    }

    @GetMapping("/skills/{id}/files")
    public List<Map<String, Object>> files(@PathVariable long id) {
        return repository.files(id);
    }

    @GetMapping("/skills/{id}/files/content")
    public ResponseEntity<byte[]> file(@PathVariable long id, @RequestParam String path) {
        Map<String, Object> metadata = repository.file(id, path);
        byte[] content = syncService.loadFileContent(id, path);
        String contentType = String.valueOf(metadata.get("content_type"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType)).body(content);
    }

    @GetMapping("/skills/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id) throws IOException {
        Map<String, Object> detail = repository.detail(id);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) detail.get("files");
            for (Map<String, Object> item : files) {
                String path = String.valueOf(item.get("path"));
                if (Boolean.TRUE.equals(item.get("is_binary")) && ((Number) item.get("size_bytes")).longValue() > 50L * 1024L * 1024L) {
                    throw new IllegalArgumentException("发现Skill包含超过 50MB 的文件");
                }
                byte[] content = syncService.loadFileContent(id, path);
                zip.putNextEntry(new ZipEntry(path));
                zip.write(content);
                zip.closeEntry();
            }
        }
        repository.incrementDownloadCount(id);
        String name = String.valueOf(detail.get("name")).replaceAll("[^a-zA-Z0-9._-]+", "-");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(output.toByteArray());
    }

}
