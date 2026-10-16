package com.km.skillhub.observation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Observation data retention (aligned with kmplm's retention config).
 *
 * <p>Disabled by default: purgeExpired() is a no-op unless enabled=true, and
 * each tier is skipped when its day count is 0 or negative. Purge is a side
 * path — failures are logged and never affect ingestion or queries.</p>
 */
@Service
public class ObservationRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ObservationRetentionService.class);

    private final ObservationRetentionRepository repository;
    private final boolean enabled;
    private final int stepDays;
    private final int sessionDays;

    public ObservationRetentionService(ObservationRetentionRepository repository,
                                       @Value("${skillhub.observation.retention.enabled:false}") boolean enabled,
                                       @Value("${skillhub.observation.retention.step-days:90}") int stepDays,
                                       @Value("${skillhub.observation.retention.session-days:180}") int sessionDays) {
        this.repository = repository;
        this.enabled = enabled;
        this.stepDays = stepDays;
        this.sessionDays = sessionDays;
    }

    @Scheduled(cron = "${skillhub.observation.retention.cron:0 30 4 * * ?}")
    public void purgeExpired() {
        if (!enabled) {
            return;
        }
        if (stepDays > 0) {
            try {
                int removedSteps = repository.deleteStepsBefore(cutoff(stepDays));
                if (removedSteps > 0) {
                    log.info("观测保留策略：删除 {} 天前观测步骤 {} 条", stepDays, removedSteps);
                }
            } catch (RuntimeException ex) {
                log.warn("观测保留策略：步骤清理失败（旁路，不影响主链路）", ex);
            }
        }
        if (sessionDays > 0) {
            // V4 order: turns (child of session) first, sessions last so
            // cascades take the remaining turns/steps and V16 summary rows.
            try {
                int removedTurns = repository.deleteTurnsBefore(cutoff(sessionDays));
                if (removedTurns > 0) {
                    log.info("观测保留策略：删除 {} 天前会话轮次 {} 条", sessionDays, removedTurns);
                }
            } catch (RuntimeException ex) {
                log.warn("观测保留策略：轮次清理失败（旁路，不影响主链路）", ex);
            }
            try {
                int removedSessions = repository.deleteSessionsBefore(cutoff(sessionDays));
                if (removedSessions > 0) {
                    log.info("观测保留策略：删除 {} 天前会话 {} 条（级联清理步骤/轮次/摘要）", sessionDays, removedSessions);
                }
            } catch (RuntimeException ex) {
                log.warn("观测保留策略：会话清理失败（旁路，不影响主链路）", ex);
            }
        }
    }

    static Instant cutoff(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }
}
