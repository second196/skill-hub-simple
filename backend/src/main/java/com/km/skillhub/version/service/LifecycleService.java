package com.km.skillhub.version.service;

import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.version.domain.SkillLifecycle;
import com.km.skillhub.version.model.VersionTransitionCommand;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LifecycleService {

    private final SkillVersionMapper versionMapper;

    public LifecycleService(SkillVersionMapper versionMapper) {
        this.versionMapper = versionMapper;
    }

    @Transactional
    public SkillVersionEntity transition(VersionTransitionCommand command) {
        if (command == null || blank(command.getVersionDigest()) || blank(command.getTargetState())
                || blank(command.getReason())) {
            throw new IllegalArgumentException("Version transition is incomplete");
        }
        SkillVersionEntity current = versionMapper.findByDigest(command.getVersionDigest());
        if (current == null) {
            throw new IllegalArgumentException("Version not found");
        }
        SkillLifecycle source = SkillLifecycle.valueOf(current.getLifecycleState());
        SkillLifecycle target = SkillLifecycle.valueOf(command.getTargetState());
        if (!source.canTransitionTo(target)) {
            throw new IllegalArgumentException("Lifecycle transition is not allowed");
        }
        int updated = versionMapper.updateLifecycleState(command.getVersionDigest(), source.name(), target.name());
        if (updated != 1) {
            throw new IllegalStateException("Version changed concurrently");
        }
        current.setLifecycleState(target.name());
        return current;
    }

    public SkillVersionEntity findByDigest(String versionDigest) {
        if (blank(versionDigest)) {
            throw new IllegalArgumentException("Version digest is required");
        }
        return versionMapper.findByDigest(versionDigest);
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
