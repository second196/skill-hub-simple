package com.km.skillhub.service;

import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.installation.mapper.InstallationInstanceMapper;
import com.km.skillhub.installation.mapper.InstallationOperationMapper;
import com.km.skillhub.installation.model.dto.RevocationRequest;
import com.km.skillhub.installation.model.entity.InstallationInstanceEntity;
import com.km.skillhub.installation.model.vo.RevocationResultVO;
import com.km.skillhub.integration.artifact.ArtifactAccessResolver;
import com.km.skillhub.integration.event.InstallationCommandPublisher;
import com.km.skillhub.release.mapper.ReleaseBindingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmergencyRevocationServiceTest {
    @Test
    void blocksBindingAndQueuesOneOperationPerInstance() {
        AuthorizationService authorization = mock(AuthorizationService.class);
        ReleaseBindingMapper bindingMapper = mock(ReleaseBindingMapper.class);
        InstallationInstanceMapper instanceMapper = mock(InstallationInstanceMapper.class);
        InstallationOperationMapper operationMapper = mock(InstallationOperationMapper.class);
        InstallationCommandPublisher publisher = mock(InstallationCommandPublisher.class);
        InstallationInstanceEntity first = instance(1L);
        InstallationInstanceEntity second = instance(2L);
        when(instanceMapper.findByScopeAndVersion(9L, digest())).thenReturn(Arrays.asList(first, second));
        when(operationMapper.insert(any())).thenReturn(1);
        when(instanceMapper.updateProgress(any())).thenReturn(1);

        RevocationRequest request = new RevocationRequest();
        request.setVersionDigest(digest()); request.setScopeType("COMPANY"); request.setScopeId(9L);
        RevocationResultVO result = new com.km.skillhub.installation.service.EmergencyRevocationService(
                authorization, bindingMapper, instanceMapper, operationMapper, publisher,
                new ArtifactAccessResolver()).revoke(request, "revoke-1", "admin");

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getQueued());
        assertEquals(2, result.getOperationIds().size());
        verify(bindingMapper).revokeCurrent(digest(), "COMPANY", 9L);
        verify(operationMapper, org.mockito.Mockito.times(2)).insert(any());
        verify(publisher, org.mockito.Mockito.times(2)).publish(any());
    }

    private InstallationInstanceEntity instance(Long id) {
        InstallationInstanceEntity value = new InstallationInstanceEntity();
        value.setId(id); value.setAssetId(3L); value.setCurrentVersionDigest(digest());
        value.setRuntimeKey("codex-cli"); value.setRuntimeVersion("initial"); value.setScopeId(9L);
        return value;
    }

    private String digest() {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) result.append('a');
        return result.toString();
    }
}
