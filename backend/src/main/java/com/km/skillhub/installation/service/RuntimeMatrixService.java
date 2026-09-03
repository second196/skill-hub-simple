package com.km.skillhub.installation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.installation.mapper.RuntimeDefinitionMapper;
import com.km.skillhub.installation.mapper.SkillRuntimeCompatibilityMapper;
import com.km.skillhub.installation.model.entity.RuntimeDefinitionEntity;
import com.km.skillhub.installation.model.vo.RuntimeDefinitionVO;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class RuntimeMatrixService {
    private final RuntimeDefinitionMapper runtimeDefinitionMapper;
    private final SkillRuntimeCompatibilityMapper compatibilityMapper;
    private final ObjectMapper objectMapper;

    public RuntimeMatrixService(RuntimeDefinitionMapper runtimeDefinitionMapper,
                                 SkillRuntimeCompatibilityMapper compatibilityMapper,
                                 ObjectMapper objectMapper) {
        this.runtimeDefinitionMapper = runtimeDefinitionMapper;
        this.compatibilityMapper = compatibilityMapper;
        this.objectMapper = objectMapper;
    }

    public void requireSkillInstallation(String versionDigest, String runtimeKey, String runtimeVersion) {
        RuntimeDefinitionEntity definition = runtimeDefinitionMapper.find(runtimeKey, runtimeVersion);
        if (definition == null || !"ACTIVE".equals(definition.getStatus())
                || !capability(definition.getCapabilities(), "installSkill")) {
            throw new IllegalArgumentException("Runtime capability does not support Skill installation");
        }
        if (!"SUPPORTED".equals(compatibilityMapper.findSupportStatus(versionDigest, runtimeKey, runtimeVersion))) {
            throw new IllegalArgumentException("Skill version is not compatible with the runtime");
        }
    }

    public List<RuntimeDefinitionVO> activeDefinitions() {
        List<RuntimeDefinitionEntity> entities = runtimeDefinitionMapper.findActive();
        if (entities == null) return Collections.emptyList();
        List<RuntimeDefinitionVO> result = new ArrayList<RuntimeDefinitionVO>();
        for (RuntimeDefinitionEntity entity : entities) {
            result.add(new RuntimeDefinitionVO(entity.getRuntimeKey(), entity.getRuntimeVersion(),
                    entity.getStatus(), entity.getCapabilities()));
        }
        return result;
    }

    private boolean capability(String value, String name) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            JsonNode node = objectMapper.readTree(value);
            return node.path(name).asBoolean(false);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Runtime capability configuration is invalid");
        }
    }
}
