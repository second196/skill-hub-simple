package com.km.skillhub.installation.service;

import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.installation.mapper.RuntimeIntegrationEventMapper;
import com.km.skillhub.installation.mapper.RuntimeIntegrationInstanceMapper;
import com.km.skillhub.installation.model.dto.RuntimeIntegrationEventRequest;
import com.km.skillhub.installation.model.dto.RuntimeIntegrationRegistration;
import com.km.skillhub.installation.model.entity.RuntimeIntegrationEventEntity;
import com.km.skillhub.installation.model.entity.RuntimeIntegrationInstanceEntity;
import com.km.skillhub.installation.model.vo.RuntimeIntegrationVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class RuntimeIntegrationService {
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern TOKEN = Pattern.compile("(?i)(Bearer\\s+)?sk_[A-Za-z0-9_-]+");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:[A-Z]:[\\\\/]|\\\\\\\\)[^\\s,;，；]+");
    private static final Pattern UNIX_ABSOLUTE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9:/])/(?:[^\\s,;，；]+)");
    private static final Set<String> INSTALLATION_STATES = new HashSet<String>(Arrays.asList(
            "DETECTED", "PLANNED", "SNAPSHOTTED", "APPLYING", "VERIFYING", "ACTIVE",
            "ACTION_REQUIRED", "ROLLING_BACK", "RESTORED", "FAILED", "REQUIRES_MANUAL", "DISABLED"));
    private static final Set<String> HEALTH_STATES = new HashSet<String>(Arrays.asList(
            "UNKNOWN", "HEALTHY", "DEGRADED", "UNHEALTHY"));
    private static final Set<String> RESULTS = new HashSet<String>(Arrays.asList(
            "SUCCEEDED", "FAILED", "ACTION_REQUIRED", "REQUIRES_MANUAL"));

    private final RuntimeIntegrationInstanceMapper instanceMapper;
    private final RuntimeIntegrationEventMapper eventMapper;
    private final AuthorizationService authorizationService;

    public RuntimeIntegrationService(RuntimeIntegrationInstanceMapper instanceMapper,
                                     RuntimeIntegrationEventMapper eventMapper,
                                     AuthorizationService authorizationService) {
        this.instanceMapper = instanceMapper;
        this.eventMapper = eventMapper;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public RuntimeIntegrationVO register(RuntimeIntegrationRegistration request, String actor) {
        validateRegistration(request, actor);
        authorizationService.requireOneOfRoles(actor, request.getScopeId(),
                "ASSET_CONTRIBUTOR", "GOVERNANCE_ADMIN");
        if (!instanceMapper.supportsTelemetry(request.getRuntimeKey(), request.getRuntimeVersion())) {
            throw new IllegalArgumentException("运行时未启用或不支持运行数据接入");
        }
        RuntimeIntegrationInstanceEntity entity = instanceMapper.findByTarget(
                request.getScopeId(), request.getRuntimeKey(), request.getTargetKey());
        if (entity == null) {
            RuntimeIntegrationInstanceEntity candidate = new RuntimeIntegrationInstanceEntity();
            candidate.setIntegrationId(UUID.randomUUID().toString());
            candidate.setScopeId(request.getScopeId());
            candidate.setRuntimeKey(request.getRuntimeKey());
            candidate.setTargetKey(request.getTargetKey());
            candidate.setCreatedBy(actor);
            candidate.setRowVersion(0L);
            applyRegistration(candidate, request, actor);
            if (instanceMapper.insert(candidate) == 1) {
                entity = candidate;
            } else {
                entity = instanceMapper.findByTarget(
                        request.getScopeId(), request.getRuntimeKey(), request.getTargetKey());
                if (entity == null) throw new IllegalStateException("运行时接入登记冲突，请稍后重试");
            }
        }
        if (!matches(entity, request)) {
            applyRegistration(entity, request, actor);
            if (instanceMapper.updateRegistration(entity) != 1) {
                throw new IllegalStateException("运行时接入状态已被并发修改");
            }
        }
        return requireVisible(entity.getIntegrationId(), actor);
    }

    @Transactional
    public RuntimeIntegrationVO acceptEvent(String integrationId, RuntimeIntegrationEventRequest request,
                                            String actor) {
        RuntimeIntegrationInstanceEntity instance = requireEntity(integrationId);
        authorizationService.requireOneOfRoles(actor, instance.getScopeId(),
                "ASSET_CONTRIBUTOR", "GOVERNANCE_ADMIN");
        validateEvent(request);
        RuntimeIntegrationEventEntity duplicate = eventMapper.findByEventId(request.getEventId());
        if (duplicate != null) {
            if (!instance.getId().equals(duplicate.getRuntimeIntegrationInstanceId())) {
                throw new IllegalArgumentException("事件标识已被其他接入实例使用");
            }
            return new RuntimeIntegrationVO(instance);
        }
        if (request.getEventSequence() <= valueOrZero(instance.getLastEventSequence())) {
            throw new IllegalArgumentException("接入事件序列早于当前状态");
        }

        RuntimeIntegrationEventEntity event = toEvent(instance.getId(), request);
        if (eventMapper.insert(event) != 1) {
            duplicate = eventMapper.findByEventId(request.getEventId());
            if (duplicate != null) {
                if (!instance.getId().equals(duplicate.getRuntimeIntegrationInstanceId())) {
                    throw new IllegalArgumentException("事件标识已被其他接入实例使用");
                }
                return requireVisible(integrationId, actor);
            }
            RuntimeIntegrationInstanceEntity current = requireEntity(integrationId);
            if (request.getEventSequence() <= valueOrZero(current.getLastEventSequence())) {
                throw new IllegalArgumentException("接入事件序列早于当前状态");
            }
            throw new IllegalStateException("运行时接入事件发生并发冲突，请稍后重试");
        }
        instance.setInstallationState(request.getInstallationState());
        instance.setHealthStatus(request.getHealthStatus());
        instance.setLastEventSequence(request.getEventSequence());
        instance.setFailureStage(trimToNull(request.getFailureStage(), 64));
        instance.setErrorCode(trimToNull(request.getErrorCode(), 64));
        instance.setErrorReason(sanitizeReason(request.getErrorReason()));
        instance.setLastReportedAt(request.getOccurredAt());
        instance.setUpdatedBy(actor);
        if (instanceMapper.updateFromEvent(instance) != 1) {
            throw new IllegalStateException("运行时接入状态已被并发修改");
        }
        return requireVisible(integrationId, actor);
    }

    public RuntimeIntegrationVO find(String integrationId, String actor) {
        return requireVisible(integrationId, actor);
    }

    public List<RuntimeIntegrationVO> list(Long scopeId, String runtimeKey, Integer limit, String actor) {
        if (blank(actor)) throw new IllegalArgumentException("当前账户不能为空");
        int actualLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        List<RuntimeIntegrationVO> result = new ArrayList<RuntimeIntegrationVO>();
        List<RuntimeIntegrationInstanceEntity> entities = instanceMapper.findAuthorized(
                actor, scopeId, trimToNull(runtimeKey, 64), actualLimit);
        if (entities == null) return result;
        for (RuntimeIntegrationInstanceEntity entity : entities) result.add(new RuntimeIntegrationVO(entity));
        return result;
    }

    private RuntimeIntegrationVO requireVisible(String integrationId, String actor) {
        RuntimeIntegrationInstanceEntity entity = requireEntity(integrationId);
        authorizationService.requireOneOfRoles(actor, entity.getScopeId(),
                "ASSET_CONTRIBUTOR", "REVIEWER", "GOVERNANCE_ADMIN", "AUDITOR");
        return new RuntimeIntegrationVO(entity);
    }

    private RuntimeIntegrationInstanceEntity requireEntity(String integrationId) {
        if (blank(integrationId)) throw new IllegalArgumentException("运行时接入标识不能为空");
        RuntimeIntegrationInstanceEntity entity = instanceMapper.findByIntegrationId(integrationId.trim());
        if (entity == null) throw new IllegalArgumentException("运行时接入实例不存在");
        return entity;
    }

    private void validateRegistration(RuntimeIntegrationRegistration request, String actor) {
        if (request == null || request.getScopeId() == null || blank(actor)
                || blank(request.getRuntimeKey()) || blank(request.getRuntimeVersion())
                || blank(request.getAdapterVersion())) {
            throw new IllegalArgumentException("运行时接入登记信息不完整");
        }
        requireDigest(request.getTargetKey(), "目标逻辑标识");
        requireDigest(request.getConfigurationDigest(), "配置摘要");
        requireState(request.getInstallationState(), INSTALLATION_STATES, "安装状态");
        requireState(request.getHealthStatus(), HEALTH_STATES, "健康状态");
        requireLength(request.getRuntimeKey(), 64, "运行时标识");
        requireLength(request.getRuntimeVersion(), 64, "运行时版本");
        requireLength(request.getAdapterVersion(), 64, "适配器版本");
    }

    private void validateEvent(RuntimeIntegrationEventRequest request) {
        if (request == null || blank(request.getEventId()) || request.getEventSequence() == null
                || request.getEventSequence() <= 0 || blank(request.getEventType()) || blank(request.getStage())
                || request.getOccurredAt() == null) {
            throw new IllegalArgumentException("运行时接入事件信息不完整");
        }
        requireLength(request.getEventId(), 128, "事件标识");
        requireLength(request.getEventType(), 64, "事件类型");
        requireLength(request.getStage(), 64, "事件阶段");
        requireState(request.getResult(), RESULTS, "事件结果");
        requireState(request.getInstallationState(), INSTALLATION_STATES, "安装状态");
        requireState(request.getHealthStatus(), HEALTH_STATES, "健康状态");
    }

    private void applyRegistration(RuntimeIntegrationInstanceEntity entity,
                                   RuntimeIntegrationRegistration request, String actor) {
        entity.setRuntimeVersion(request.getRuntimeVersion().trim());
        entity.setAdapterVersion(request.getAdapterVersion().trim());
        entity.setConfigurationDigest(request.getConfigurationDigest());
        entity.setInstallationState(request.getInstallationState());
        entity.setHealthStatus(request.getHealthStatus());
        entity.setFailureStage(trimToNull(request.getFailureStage(), 64));
        entity.setErrorCode(trimToNull(request.getErrorCode(), 64));
        entity.setErrorReason(sanitizeReason(request.getErrorReason()));
        entity.setLastReportedAt(OffsetDateTime.now());
        entity.setUpdatedBy(actor);
    }

    private boolean matches(RuntimeIntegrationInstanceEntity entity, RuntimeIntegrationRegistration request) {
        return request.getRuntimeVersion().equals(entity.getRuntimeVersion())
                && request.getAdapterVersion().equals(entity.getAdapterVersion())
                && request.getConfigurationDigest().equals(entity.getConfigurationDigest())
                && request.getInstallationState().equals(entity.getInstallationState())
                && request.getHealthStatus().equals(entity.getHealthStatus())
                && same(trimToNull(request.getFailureStage(), 64), entity.getFailureStage())
                && same(trimToNull(request.getErrorCode(), 64), entity.getErrorCode())
                && same(sanitizeReason(request.getErrorReason()), entity.getErrorReason());
    }

    private RuntimeIntegrationEventEntity toEvent(Long instanceId, RuntimeIntegrationEventRequest request) {
        RuntimeIntegrationEventEntity event = new RuntimeIntegrationEventEntity();
        event.setEventId(request.getEventId().trim());
        event.setRuntimeIntegrationInstanceId(instanceId);
        event.setEventSequence(request.getEventSequence());
        event.setEventType(request.getEventType().trim());
        event.setStage(request.getStage().trim());
        event.setResult(request.getResult());
        event.setInstallationState(request.getInstallationState());
        event.setHealthStatus(request.getHealthStatus());
        event.setFailureStage(trimToNull(request.getFailureStage(), 64));
        event.setErrorCode(trimToNull(request.getErrorCode(), 64));
        event.setErrorReason(sanitizeReason(request.getErrorReason()));
        event.setOccurredAt(request.getOccurredAt());
        return event;
    }

    private void requireDigest(String value, String label) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "必须是 64 位小写 SHA-256 摘要");
        }
    }

    private void requireState(String value, Set<String> values, String label) {
        if (value == null || !values.contains(value)) throw new IllegalArgumentException(label + "不受支持");
    }

    private void requireLength(String value, int maximum, String label) {
        if (value.trim().length() > maximum) throw new IllegalArgumentException(label + "长度超过限制");
    }

    private String sanitizeReason(String value) {
        String reason = trimToNull(value, 2048);
        if (reason == null) return null;
        reason = TOKEN.matcher(reason).replaceAll("[访问凭证已脱敏]");
        reason = WINDOWS_ABSOLUTE_PATH.matcher(reason).replaceAll("[本地路径已脱敏]");
        return UNIX_ABSOLUTE_PATH.matcher(reason).replaceAll("[本地路径已脱敏]");
    }

    private String trimToNull(String value, int maximum) {
        if (value == null || value.trim().isEmpty()) return null;
        String result = value.trim();
        return result.length() <= maximum ? result : result.substring(0, maximum);
    }

    private int valueOrZero(Integer value) { return value == null ? 0 : value; }
    private boolean same(String left, String right) { return left == null ? right == null : left.equals(right); }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
