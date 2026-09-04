package com.km.skillhub.telemetry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.telemetry.domain.TelemetryErrorCode;
import com.km.skillhub.telemetry.mapper.TelemetryIngestBatchMapper;
import com.km.skillhub.telemetry.model.dto.OtlpLogRequest;
import com.km.skillhub.telemetry.model.dto.OtlpTraceRequest;
import com.km.skillhub.telemetry.model.entity.TelemetryIngestBatchEntity;
import com.km.skillhub.telemetry.model.vo.TelemetryIngestBatchVO;
import com.km.skillhub.telemetry.model.vo.TelemetryIngestResultVO;
import com.km.skillhub.telemetry.store.TelemetryAppendResult;
import com.km.skillhub.telemetry.store.TelemetryEventStore;
import com.km.skillhub.token.security.ApiTokenAuthentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TelemetryIngestService {
    private final OtlpJsonNormalizationService normalizationService;
    private final TelemetryEventStore eventStore;
    private final TelemetryIngestBatchMapper batchMapper;
    private final AuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    private final int maxEvents;

    public TelemetryIngestService(OtlpJsonNormalizationService normalizationService,
                                  TelemetryEventStore eventStore,
                                  TelemetryIngestBatchMapper batchMapper,
                                  AuthorizationService authorizationService,
                                  ObjectMapper objectMapper,
                                  @Value("${skillhub.telemetry.ingest.max-events:1000}") int maxEvents) {
        this.normalizationService = normalizationService;
        this.eventStore = eventStore;
        this.batchMapper = batchMapper;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
        this.maxEvents = maxEvents;
    }

    public TelemetryIngestResultVO ingestTraces(String requestId, byte[] payload,
                                                 ApiTokenAuthentication authentication) {
        return ingest(requestId, payload, authentication, true);
    }

    public TelemetryIngestResultVO ingestLogs(String requestId, byte[] payload,
                                               ApiTokenAuthentication authentication) {
        return ingest(requestId, payload, authentication, false);
    }

    public TelemetryIngestBatchVO findBatch(String requestId, String actor) {
        validateRequestId(requestId);
        TelemetryIngestBatchEntity batch = batchMapper.findByRequestId(requestId);
        if (batch == null) throw new TelemetryIngestException(TelemetryErrorCode.BATCH_NOT_FOUND);
        requireReadScope(actor, batch.getScopeId());
        return toBatchVO(batch);
    }

    private TelemetryIngestResultVO ingest(String requestId, byte[] payload,
                                            ApiTokenAuthentication authentication, boolean traces) {
        validateRequestId(requestId);
        if (authentication == null) throw new TelemetryIngestException(TelemetryErrorCode.BEARER_TOKEN_REQUIRED);
        if (payload == null || payload.length == 0) {
            throw new TelemetryIngestException(TelemetryErrorCode.INVALID_OTLP_STRUCTURE);
        }
        String payloadDigest = digest(payload);
        TelemetryIngestBatchEntity existing = batchMapper.findByRequestId(requestId);
        if (existing != null) return existingResult(existing, payloadDigest, authentication.getName());

        OtlpJsonNormalizationService.NormalizationResult normalized = traces
                ? normalizationService.normalizeTraces(read(payload, OtlpTraceRequest.class))
                : normalizationService.normalizeLogs(read(payload, OtlpLogRequest.class));
        if (normalized.getReceived() > maxEvents) {
            throw new TelemetryIngestException(TelemetryErrorCode.BATCH_TOO_LARGE);
        }
        authorizationService.requireOneOfRoles(authentication.getName(), normalized.getScopeId(),
                "ASSET_CONTRIBUTOR", "GOVERNANCE_ADMIN");

        TelemetryIngestBatchEntity batch = batch(requestId, payloadDigest, authentication, normalized);
        try {
            TelemetryAppendResult appended = eventStore.appendBatch(batch, normalized.getEvents());
            return new TelemetryIngestResultVO(requestId, appended.getBatchId(), appended.getAccepted(),
                    appended.getDuplicate(), appended.getRejected(), appended.getStatus(),
                    normalized.getRejectionReasons());
        } catch (DataIntegrityViolationException exception) {
            TelemetryIngestBatchEntity concurrent = batchMapper.findByRequestId(requestId);
            if (concurrent == null) throw exception;
            return existingResult(concurrent, payloadDigest, authentication.getName());
        }
    }

    private TelemetryIngestBatchEntity batch(String requestId, String payloadDigest,
                                              ApiTokenAuthentication authentication,
                                              OtlpJsonNormalizationService.NormalizationResult normalized) {
        TelemetryIngestBatchEntity batch = new TelemetryIngestBatchEntity();
        batch.setRequestId(requestId);
        batch.setActorId(authentication.getName());
        batch.setTokenId(String.valueOf(authentication.getTokenId()));
        batch.setScopeId(normalized.getScopeId());
        batch.setRuntimeKey(normalized.getRuntimeKey());
        batch.setPayloadDigest(payloadDigest);
        batch.setReceivedCount(normalized.getReceived());
        batch.setRejectedCount(normalized.getRejected());
        batch.setStatus(status(normalized));
        batch.setErrorSummary(json(normalized.getRejectionReasons()));
        return batch;
    }

    private String status(OtlpJsonNormalizationService.NormalizationResult normalized) {
        if (normalized.getRejected() == 0) return "ACCEPTED";
        return normalized.getEvents().isEmpty() ? "REJECTED" : "PARTIAL";
    }

    private TelemetryIngestResultVO existingResult(TelemetryIngestBatchEntity batch, String digest, String actor) {
        requireReadScope(actor, batch.getScopeId());
        if (!digest.equals(batch.getPayloadDigest())) {
            throw new TelemetryIngestException(TelemetryErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return new TelemetryIngestResultVO(batch.getRequestId(), batch.getId(), value(batch.getAcceptedCount()),
                value(batch.getDuplicateCount()), value(batch.getRejectedCount()), batch.getStatus(),
                rejectionReasons(batch.getErrorSummary()));
    }

    private TelemetryIngestBatchVO toBatchVO(TelemetryIngestBatchEntity batch) {
        return new TelemetryIngestBatchVO(batch.getId(), batch.getRequestId(), batch.getScopeId(),
                batch.getRuntimeKey(), value(batch.getReceivedCount()), value(batch.getAcceptedCount()),
                value(batch.getDuplicateCount()), value(batch.getRejectedCount()), batch.getStatus(),
                rejectionReasons(batch.getErrorSummary()), batch.getReceivedAt(), batch.getCompletedAt());
    }

    private void requireReadScope(String actor, Long scopeId) {
        authorizationService.requireOneOfRoles(actor, scopeId, "ASSET_CONTRIBUTOR", "REVIEWER",
                "GOVERNANCE_ADMIN", "AUDITOR");
    }

    private void validateRequestId(String requestId) {
        if (requestId == null || requestId.trim().isEmpty() || requestId.trim().length() > 128) {
            throw new TelemetryIngestException(TelemetryErrorCode.INVALID_REQUEST_ID);
        }
    }

    private <T> T read(byte[] payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (Exception exception) {
            throw new TelemetryIngestException(TelemetryErrorCode.INVALID_OTLP_STRUCTURE);
        }
    }

    private Map<String, Integer> rejectionReasons(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(value, new TypeReference<LinkedHashMap<String, Integer>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("遥测批次拒绝摘要无效", exception);
        }
    }

    private String json(Map<String, Integer> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化遥测拒绝摘要", exception);
        }
    }

    private String digest(byte[] payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder result = new StringBuilder(64);
            for (byte item : hash) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    public static class TelemetryIngestException extends RuntimeException {
        private final TelemetryErrorCode errorCode;
        public TelemetryIngestException(TelemetryErrorCode errorCode) {
            super(errorCode.getMessage());
            this.errorCode = errorCode;
        }
        public TelemetryErrorCode getErrorCode() { return errorCode; }
    }
}
