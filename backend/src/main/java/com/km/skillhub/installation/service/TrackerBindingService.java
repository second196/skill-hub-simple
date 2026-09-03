package com.km.skillhub.installation.service;

import com.km.skillhub.installation.mapper.TrackerBindingMapper;
import com.km.skillhub.installation.model.entity.TrackerBindingEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class TrackerBindingService {
    public static final String DEFAULT_TRACKER_KEY = "skill-tracker";
    private final TrackerBindingMapper trackerBindingMapper;

    public TrackerBindingService(TrackerBindingMapper trackerBindingMapper) {
        this.trackerBindingMapper = trackerBindingMapper;
    }

    @Transactional
    public TrackerBindingEntity prepare(Long instanceId, String trackerKey, String trackerVersion,
                                        String configurationDigest, String actor) {
        if (instanceId == null || blank(trackerKey) || blank(trackerVersion) || blank(actor)) {
            throw new IllegalArgumentException("Tracker binding is incomplete");
        }
        TrackerBindingEntity current = trackerBindingMapper.findByInstanceAndKey(instanceId, trackerKey);
        if (current != null) {
            if (!trackerVersion.equals(current.getTrackerVersion())
                    || !same(configurationDigest, current.getConfigurationDigest())) {
                current.setTrackerVersion(trackerVersion);
                current.setConfigurationDigest(configurationDigest);
                current.setUpdatedBy(actor);
                current.setRowVersion(current.getRowVersion() == null ? 0L : current.getRowVersion());
                trackerBindingMapper.updateDefinition(current);
            }
            return current;
        }
        TrackerBindingEntity binding = new TrackerBindingEntity();
        binding.setInstallationInstanceId(instanceId);
        binding.setTrackerKey(trackerKey);
        binding.setTrackerVersion(trackerVersion);
        binding.setConfigurationDigest(configurationDigest);
        binding.setInstallationState("NOT_INSTALLED");
        binding.setHealthState("UNKNOWN");
        binding.setCreatedBy(actor);
        binding.setUpdatedBy(actor);
        binding.setCreatedAt(OffsetDateTime.now());
        binding.setUpdatedAt(binding.getCreatedAt());
        trackerBindingMapper.insert(binding);
        return binding;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
