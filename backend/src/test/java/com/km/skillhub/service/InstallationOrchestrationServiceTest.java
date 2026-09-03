package com.km.skillhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.installation.mapper.InstallationInstanceMapper;
import com.km.skillhub.installation.mapper.InstallationOperationMapper;
import com.km.skillhub.installation.mapper.RuntimeDefinitionMapper;
import com.km.skillhub.installation.mapper.SkillRuntimeCompatibilityMapper;
import com.km.skillhub.installation.model.dto.InstallationRequest;
import com.km.skillhub.installation.model.entity.RuntimeDefinitionEntity;
import com.km.skillhub.installation.model.entity.InstallationOperationEntity;
import com.km.skillhub.installation.service.InstallationOrchestrationService;
import com.km.skillhub.installation.service.RuntimeMatrixService;
import com.km.skillhub.installation.model.entity.InstallationInstanceEntity;
import com.km.skillhub.release.mapper.ReleaseBindingMapper;
import com.km.skillhub.release.model.entity.ReleaseBindingEntity;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import com.km.skillhub.version.service.LifecycleService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstallationOrchestrationServiceTest {
    @Test
    void createsAnInstallOperationOnlyForTheActiveReleasedVersion() {
        InstallationInstanceMapper instanceMapper = mock(InstallationInstanceMapper.class);
        InstallationOperationMapper operationMapper = mock(InstallationOperationMapper.class);
        ReleaseBindingMapper bindingMapper = mock(ReleaseBindingMapper.class);
        LifecycleService lifecycleService = mock(LifecycleService.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        RuntimeMatrixService runtimeMatrix = runtimeMatrix(true, "SUPPORTED");
        AuditQueryService audit = mock(AuditQueryService.class);
        InstallationOrchestrationService service = new InstallationOrchestrationService(instanceMapper, operationMapper,
                bindingMapper, lifecycleService, authorizationService, runtimeMatrix, audit);

        String digest = repeat('a', 64);
        SkillVersionEntity version = new SkillVersionEntity(); version.setAssetId(7L); version.setLifecycleState("PUBLISHED");
        ReleaseBindingEntity binding = new ReleaseBindingEntity(); binding.setCurrent(true); binding.setBindingState("ACTIVE");
        binding.setVersionDigest(digest); binding.setPolicyVersion("policy-1");
        when(lifecycleService.findByDigest(digest)).thenReturn(version);
        when(bindingMapper.findCurrent(7L, "COMPANY", 1L)).thenReturn(binding);
        when(instanceMapper.findByTarget(7L, "codex-cli", "HOST", "host-1", 1L)).thenReturn(null);
        when(operationMapper.findByRequest("request-1", 101L)).thenReturn(null);
        when(instanceMapper.insert(any(InstallationInstanceEntity.class))).thenAnswer(invocation -> {
            InstallationInstanceEntity value = invocation.getArgument(0); value.setId(101L); return 1;
        });
        when(operationMapper.insert(any(InstallationOperationEntity.class))).thenAnswer(invocation -> {
            InstallationOperationEntity value = invocation.getArgument(0); value.setId(201L); return 1;
        });

        assertEquals("INSTALL", service.requestInstallation(request(digest), "request-1", "admin").getOperationType());
    }

    @Test
    void rejectsAReleaseVersionThatIsNotPublished() {
        LifecycleService lifecycleService = mock(LifecycleService.class);
        SkillVersionEntity version = new SkillVersionEntity(); version.setAssetId(7L); version.setLifecycleState("CANDIDATE");
        when(lifecycleService.findByDigest(anyString())).thenReturn(version);
        InstallationOrchestrationService service = new InstallationOrchestrationService(mock(InstallationInstanceMapper.class),
                mock(InstallationOperationMapper.class), mock(ReleaseBindingMapper.class), lifecycleService,
                mock(AuthorizationService.class), runtimeMatrix(true, "SUPPORTED"), mock(AuditQueryService.class));
        assertThrows(IllegalArgumentException.class, () -> service.requestInstallation(request(repeat('b', 64)), "request-2", "admin"));
    }

    private InstallationRequest request(String digest) {
        InstallationRequest request = new InstallationRequest(); request.setAssetId(7L); request.setVersionDigest(digest);
        request.setScopeType("COMPANY"); request.setScopeId(1L); request.setRuntimeKey("codex-cli");
        request.setRuntimeVersion("1"); request.setTargetType("HOST"); request.setTargetKey("host-1"); return request;
    }

    private RuntimeMatrixService runtimeMatrix(boolean install, String support) {
        RuntimeDefinitionMapper runtimeMapper = mock(RuntimeDefinitionMapper.class);
        SkillRuntimeCompatibilityMapper compatibilityMapper = mock(SkillRuntimeCompatibilityMapper.class);
        RuntimeDefinitionEntity definition = new RuntimeDefinitionEntity(); definition.setStatus("ACTIVE");
        definition.setCapabilities("{\"installSkill\":" + install + "}");
        when(runtimeMapper.find("codex-cli", "1")).thenReturn(definition);
        when(compatibilityMapper.findSupportStatus(anyString(), anyString(), anyString())).thenReturn(support);
        return new RuntimeMatrixService(runtimeMapper, compatibilityMapper, new ObjectMapper());
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count); for (int i = 0; i < count; i++) result.append(value); return result.toString();
    }
}
