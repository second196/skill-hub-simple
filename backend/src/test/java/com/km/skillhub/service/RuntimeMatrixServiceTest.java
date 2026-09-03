package com.km.skillhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.installation.mapper.RuntimeDefinitionMapper;
import com.km.skillhub.installation.mapper.SkillRuntimeCompatibilityMapper;
import com.km.skillhub.installation.model.entity.RuntimeDefinitionEntity;
import com.km.skillhub.installation.service.RuntimeMatrixService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeMatrixServiceTest {
    @Test
    void blocksAConfiguredRuntimeWithoutInstallCapability() {
        RuntimeDefinitionMapper definitions = mock(RuntimeDefinitionMapper.class);
        SkillRuntimeCompatibilityMapper compatibility = mock(SkillRuntimeCompatibilityMapper.class);
        RuntimeDefinitionEntity definition = new RuntimeDefinitionEntity(); definition.setStatus("ACTIVE");
        definition.setCapabilities("{\"installSkill\":false}");
        when(definitions.find("claude-code-otlp", "initial")).thenReturn(definition);
        assertThrows(IllegalArgumentException.class, () -> new RuntimeMatrixService(definitions, compatibility,
                new ObjectMapper()).requireSkillInstallation(repeat('a', 64), "claude-code-otlp", "initial"));
    }

    @Test
    void blocksInvalidCapabilityJson() {
        RuntimeDefinitionMapper definitions = mock(RuntimeDefinitionMapper.class);
        SkillRuntimeCompatibilityMapper compatibility = mock(SkillRuntimeCompatibilityMapper.class);
        RuntimeDefinitionEntity definition = new RuntimeDefinitionEntity(); definition.setStatus("ACTIVE");
        definition.setCapabilities("invalid");
        when(definitions.find(anyString(), anyString())).thenReturn(definition);
        assertThrows(IllegalArgumentException.class, () -> new RuntimeMatrixService(definitions, compatibility,
                new ObjectMapper()).requireSkillInstallation(repeat('a', 64), "codex-cli", "initial"));
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count); for (int i = 0; i < count; i++) result.append(value); return result.toString();
    }
}
