package com.km.skillhub.telemetry.controller;

import com.km.skillhub.telemetry.model.vo.TelemetryIngestBatchVO;
import com.km.skillhub.telemetry.service.TelemetryIngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/telemetry/ingest-batches")
public class TelemetryIngestBatchController {
    private final TelemetryIngestService ingestService;

    public TelemetryIngestBatchController(TelemetryIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<?> find(@PathVariable String requestId, Authentication authentication) {
        try {
            TelemetryIngestBatchVO result = ingestService.findBatch(requestId, authentication.getName());
            return ResponseEntity.ok(result);
        } catch (TelemetryIngestService.TelemetryIngestException exception) {
            return TelemetryIngestController.error(exception);
        }
    }
}
