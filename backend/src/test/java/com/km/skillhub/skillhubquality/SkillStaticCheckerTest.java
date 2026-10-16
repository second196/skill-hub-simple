package com.km.skillhub.skillhubquality;

import com.km.skillhub.skillhubquality.SkillStaticChecker.Issue;
import com.km.skillhub.skillhubquality.SkillStaticChecker.SkillFile;
import com.km.skillhub.skillhubquality.SkillStaticChecker.StaticReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L1 五项格式检查：空包降级、合格包通过、缺失结构定位到对应指标。 */
class SkillStaticCheckerTest {

    private final SkillStaticChecker checker = new SkillStaticChecker(15, 5);

    private static final String GOOD_SKILL_MD =
            "---\n"
                    + "name: demo-skill\n"
                    + "description: 演示技能，用于测试质量报告的格式检查与降级结构是否符合契约。\n"
                    + "version: 1.0.0\n"
                    + "---\n"
                    + "\n"
                    + "# Demo\n"
                    + "\n"
                    + "1. 运行 scripts/run.py 执行导出\n"
                    + "2. 校验输出文件是否存在\n"
                    + "\n"
                    + "失败时重试一次，仍失败则退出。\n"
                    + "不要跳过校验步骤。\n";

    @Test
    void emptyPackageFailsWithErrorIssue() {
        StaticReport report = checker.check(Collections.<SkillFile>emptyList());

        // 1 个 error × 15 分罚分 → 85，status=error（与 kmplm buildReport 口径一致）
        assertEquals(85, report.getScore());
        assertEquals("error", report.getStatus());
        assertEquals(1, report.getIssues().size());
        Issue issue = report.getIssues().get(0);
        assertEquals(SkillStaticChecker.METRIC_A, issue.getMetricCode());
        assertEquals(SkillStaticChecker.LEVEL_ERROR, issue.getLevel());
        assertTrue(issue.getMessage().contains("没有可读文件"));
    }

    @Test
    void missingSkillMdFailsMetricA() {
        StaticReport report = checker.check(Arrays.asList(
                new SkillFile("scripts/run.py", "print('x')")));

        assertEquals("error", report.getStatus());
        boolean skillMdMissing = false;
        for (Issue issue : report.getIssues()) {
            if (issue.getMessage().contains("未找到 SKILL.md")) skillMdMissing = true;
        }
        assertTrue(skillMdMissing);
    }

    @Test
    void wellFormedPackagePassesAllFiveMetrics() {
        StaticReport report = checker.check(Arrays.asList(
                new SkillFile("SKILL.md", GOOD_SKILL_MD),
                new SkillFile("scripts/run.py", "print('x')")));

        assertTrue(report.getIssues().isEmpty(),
                "unexpected issues: " + report.getIssues());
        assertEquals(100, report.getScore());
        assertEquals("ok", report.getStatus());
        assertEquals(0, report.getMetrics().get("errorCount"));
        assertEquals(0, report.getMetrics().get("warnCount"));
    }

    @Test
    void incompletePackageLocatesIssuesByMetric() {
        StaticReport report = checker.check(Arrays.asList(
                new SkillFile("SKILL.md", "# only a heading\n")));

        assertEquals("error", report.getStatus());
        assertTrue(report.getScore() < 100);
        List<String> codes = new ArrayList<String>();
        for (Issue issue : report.getIssues()) {
            codes.add(issue.getMetricCode());
        }
        assertTrue(codes.contains(SkillStaticChecker.METRIC_A)); // 缺 name/description
        assertTrue(codes.contains(SkillStaticChecker.METRIC_C)); // 无步骤
        assertTrue(codes.contains(SkillStaticChecker.METRIC_D)); // 无失败分支/反例
        assertFalse(codes.contains(SkillStaticChecker.METRIC_B)); // 无相对引用 → 不报
        // score = 100 − 15×error − 5×warn
        int errors = 0;
        int warns = 0;
        for (Issue issue : report.getIssues()) {
            if (SkillStaticChecker.LEVEL_ERROR.equals(issue.getLevel())) errors++;
            else warns++;
        }
        assertTrue(errors >= 1);
        assertEquals(100 - 15 * errors - 5 * warns, report.getScore());
    }

    @Test
    void missingReferencedFileIsErrorOnMetricB() {
        StaticReport report = checker.check(Arrays.asList(
                new SkillFile("SKILL.md", GOOD_SKILL_MD.replace("scripts/run.py", "scripts/missing.py"))));

        boolean missingRef = false;
        for (Issue issue : report.getIssues()) {
            if (SkillStaticChecker.METRIC_B.equals(issue.getMetricCode())
                    && SkillStaticChecker.LEVEL_ERROR.equals(issue.getLevel())
                    && issue.getMessage().contains("scripts/missing.py")) {
                missingRef = true;
            }
        }
        assertTrue(missingRef, "expected missing reference error, got: " + report.getIssues());
        assertEquals("error", report.getStatus());
    }
}
