package com.km.skillhub.skillhubquality;

import java.util.List;
import java.util.Map;

/**
 * Skill 质量报告编排。
 *
 * <ul>
 *   <li>格式检查 + skill内容检查：与版本绑定，可回填重算（scope=static）。</li>
 *   <li>skill链路分析：与版本绑定、基于该版本全部观测；每日定时刷新 + 可手动重算（scope=chain）。</li>
 * </ul>
 *
 * <p>无观测数据时返回降级结构（effect.sampleInsufficient=true、score=null、
 * 结论受 sampleOk 约束），报告始终带 {@code status} 字段，不抛 500。</p>
 */
public interface SkillQualityService {

    /** 报告查询；无已存报告时现场分析并落库。 */
    Map<String, Object> qualityReport(String slug, String version);

    /** 返回各版本的轻量质量摘要（最近 8 个版本），不触发分析。 */
    List<Map<String, Object>> qualitySummary(String slug);

    /** 只重算格式检查 + skill内容检查（回填用），沿用/刷新链路结果。 */
    void analyzeStaticOnly(String slug, String version);

    /** 全量 skill链路分析（观测指标 + 规则/AI 六维），更新并落库报告。 */
    Map<String, Object> analyzeChainForVersion(String slug, String version);

    /** 定时任务入口：对所有有观测数据的 Skill 刷新链路分析；单个失败不中断。 */
    int refreshAllChainAnalyses();
}
