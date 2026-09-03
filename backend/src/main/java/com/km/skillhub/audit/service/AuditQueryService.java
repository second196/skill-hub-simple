package com.km.skillhub.audit.service;

import com.km.skillhub.audit.mapper.AuditLogMapper;
import com.km.skillhub.audit.model.AuditLogEntity;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class AuditQueryService {
    private final AuditLogMapper auditLogMapper;
    public AuditQueryService(AuditLogMapper auditLogMapper) { this.auditLogMapper = auditLogMapper; }
    public void record(String actorId, String action, String objectType, String objectId, String reason,
                       String beforeState, String afterState, String scopeType, Long scopeId, String policyVersion) {
        AuditLogEntity entity = new AuditLogEntity(); entity.setActorId(actorId); entity.setActorType("USER");
        entity.setAction(action); entity.setObjectType(objectType); entity.setObjectId(objectId);
        entity.setReason(reason); entity.setBeforeState(beforeState == null ? "{}" : beforeState);
        entity.setAfterState(afterState == null ? "{}" : afterState); entity.setScopeType(scopeType);
        entity.setScopeId(scopeId); entity.setPolicyVersion(policyVersion); entity.setOccurredAt(OffsetDateTime.now());
        auditLogMapper.insert(entity);
    }
    public List<AuditLogEntity> recent() { return auditLogMapper.findRecent(); }
}
