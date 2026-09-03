package com.km.skillhub.gate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.gate.mapper.GateEvidenceMapper;
import com.km.skillhub.gate.model.GateEvidenceRequest;
import com.km.skillhub.gate.model.entity.GateEvidenceEntity;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class GateEvidenceService {
    private final GateEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;

    public GateEvidenceService(GateEvidenceMapper evidenceMapper, ObjectMapper objectMapper) {
        this.evidenceMapper = evidenceMapper; this.objectMapper = objectMapper;
    }

    public GateEvidenceEntity create(GateEvidenceRequest request) {
        if (request == null || blank(request.getVersionDigest()) || blank(request.getEvidenceType())
                || blank(request.getResult()) || blank(request.getProducerType()) || blank(request.getProducerId())
                || blank(request.getEvidenceDigest())) throw new IllegalArgumentException("Evidence is incomplete");
        GateEvidenceEntity entity = new GateEvidenceEntity();
        entity.setVersionDigest(request.getVersionDigest()); entity.setEvidenceType(request.getEvidenceType());
        entity.setResult(request.getResult()); entity.setProducerType(request.getProducerType());
        entity.setProducerId(request.getProducerId()); entity.setEvidenceUri(request.getEvidenceUri());
        entity.setEvidenceDigest(request.getEvidenceDigest());
        entity.setConditions(writeMetrics(request.getMetrics()));
        entity.setGeneratedAt(request.getGeneratedAt() == null ? OffsetDateTime.now() : request.getGeneratedAt());
        entity.setExpiresAt(request.getExpiresAt()); evidenceMapper.insert(entity); return entity;
    }

    private String writeMetrics(GateEvidenceRequest.GateMetrics metrics) {
        try { return objectMapper.writeValueAsString(metrics == null ? new GateEvidenceRequest.GateMetrics() : metrics); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Evidence metrics are invalid"); }
    }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
