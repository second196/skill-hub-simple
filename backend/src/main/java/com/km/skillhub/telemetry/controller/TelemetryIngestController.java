package com.km.skillhub.telemetry.controller;

import com.km.skillhub.telemetry.domain.TelemetryErrorCode;
import com.km.skillhub.telemetry.model.vo.TelemetryIngestResultVO;
import com.km.skillhub.telemetry.service.TelemetryIngestService;
import com.km.skillhub.token.security.ApiTokenAuthentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/telemetry/otlp/v1")
public class TelemetryIngestController {
    private final TelemetryIngestService ingestService;
    private final int maxPayloadBytes;

    public TelemetryIngestController(TelemetryIngestService ingestService,
                                     @Value("${skillhub.telemetry.ingest.max-payload-bytes:5242880}")
                                     int maxPayloadBytes) {
        this.ingestService = ingestService;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @PostMapping("/traces")
    public ResponseEntity<?> ingestTraces(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            HttpServletRequest request, Authentication authentication) {
        return ingest(requestId, request, authentication, true);
    }

    @PostMapping("/logs")
    public ResponseEntity<?> ingestLogs(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            HttpServletRequest request, Authentication authentication) {
        return ingest(requestId, request, authentication, false);
    }

    private ResponseEntity<?> ingest(String requestId, HttpServletRequest request,
                                     Authentication authentication, boolean traces) {
        try {
            requireJson(request.getContentType());
            if (!(authentication instanceof ApiTokenAuthentication)) {
                throw new TelemetryIngestService.TelemetryIngestException(
                        TelemetryErrorCode.BEARER_TOKEN_REQUIRED);
            }
            byte[] payload = readPayload(request);
            TelemetryIngestResultVO result = traces
                    ? ingestService.ingestTraces(requestId, payload, (ApiTokenAuthentication) authentication)
                    : ingestService.ingestLogs(requestId, payload, (ApiTokenAuthentication) authentication);
            return ResponseEntity.ok(result);
        } catch (TelemetryIngestService.TelemetryIngestException exception) {
            return error(exception);
        } catch (IOException exception) {
            return error(new TelemetryIngestService.TelemetryIngestException(
                    TelemetryErrorCode.INVALID_OTLP_STRUCTURE));
        }
    }

    private void requireJson(String contentType) {
        try {
            if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(
                    MediaType.parseMediaType(contentType))) {
                throw new TelemetryIngestService.TelemetryIngestException(
                        TelemetryErrorCode.UNSUPPORTED_MEDIA_TYPE);
            }
        } catch (IllegalArgumentException exception) {
            throw new TelemetryIngestService.TelemetryIngestException(
                    TelemetryErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    private byte[] readPayload(HttpServletRequest request) throws IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxPayloadBytes) {
            throw new TelemetryIngestService.TelemetryIngestException(TelemetryErrorCode.PAYLOAD_TOO_LARGE);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                contentLength > 0 ? (int) Math.min(contentLength, maxPayloadBytes) : 1024);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = request.getInputStream().read(buffer)) != -1) {
            total += read;
            if (total > maxPayloadBytes) {
                throw new TelemetryIngestService.TelemetryIngestException(TelemetryErrorCode.PAYLOAD_TOO_LARGE);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    public static ResponseEntity<ErrorResponse> error(
            TelemetryIngestService.TelemetryIngestException exception) {
        TelemetryErrorCode code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(new ErrorResponse(code.name(), code.getMessage()));
    }

    public static class ErrorResponse {
        private final String code;
        private final String message;
        private final OffsetDateTime occurredAt = OffsetDateTime.now();
        public ErrorResponse(String code, String message) {
            this.code = code;
            this.message = message;
        }
        public String getCode() { return code; }
        public String getMessage() { return message; }
        public OffsetDateTime getOccurredAt() { return occurredAt; }
    }
}
