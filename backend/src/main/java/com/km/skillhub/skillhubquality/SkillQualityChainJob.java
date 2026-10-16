package com.km.skillhub.skillhubquality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * skill链路分析定时任务：每日 02:00 对有观测数据的 Skill 版本做全量链路
 * 分析刷新（对齐 kmplm {@code SkillQualityChainJob}）。
 *
 * <p>格式检查与 skill内容检查在无报告时才兜底计算，链路刷新失败只记日志，
 * 不向外抛错（LLM 预算受限时内部已降级为规则结果）。</p>
 */
@Component
public class SkillQualityChainJob {

    private static final Logger log = LoggerFactory.getLogger(SkillQualityChainJob.class);

    private final SkillQualityService skillQualityService;

    public SkillQualityChainJob(SkillQualityService skillQualityService) {
        this.skillQualityService = skillQualityService;
    }

    @Scheduled(cron = "${skillhub.quality.chain-cron:0 0 2 * * ?}", zone = "${platform.timezone:Asia/Shanghai}")
    public void refreshChainAnalyses() {
        try {
            int count = skillQualityService.refreshAllChainAnalyses();
            log.info("skill chain analysis refreshed, versions={}", count);
        } catch (RuntimeException ex) {
            log.warn("skill chain analysis job failed: {}", ex.getMessage());
        }
    }
}
