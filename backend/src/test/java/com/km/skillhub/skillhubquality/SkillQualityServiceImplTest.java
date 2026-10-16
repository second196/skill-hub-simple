package com.km.skillhub.skillhubquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.analysis.AnalysisModelClient;
import com.km.skillhub.observation.ObservationRepository;
import com.km.skillhub.skill.SkillApiException;
import com.km.skillhub.skill.SkillRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告编排：无观测数据的降级结构（status 字段 + effect 契约）、存量报告
 * 读取、汇总映射、skill_quality_config 权重应用、static 回填落库、
 * 模型不可用时 wording 降级。
 */
class SkillQualityServiceImplTest {

    private static final String SCORER_JSON =
            "{\"dims\":["
                    + "{\"score\":8,\"reason\":\"理由0\",\"suggestion\":\"改法0\"},"
                    + "{\"score\":8,\"reason\":\"理由1\",\"suggestion\":\"改法1\"},"
                    + "{\"score\":8,\"reason\":\"理由2\",\"suggestion\":\"改法2\"},"
                    + "{\"score\":8,\"reason\":\"理由3\",\"suggestion\":\"改法3\"},"
                    + "{\"score\":8,\"reason\":\"理由4\",\"suggestion\":\"改法4\"},"
                    + "{\"score\":8,\"reason\":\"理由5\",\"suggestion\":\"改法5\"},"
                    + "{\"score\":8,\"reason\":\"理由6\",\"suggestion\":\"改法6\"},"
                    + "{\"score\":8,\"reason\":\"理由7\",\"suggestion\":\"改法7\"},"
                    + "{\"score\":8,\"reason\":\"理由8\",\"suggestion\":\"改法8\"}"
                    + "]}";

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

    private final ObjectMapper mapper = new ObjectMapper();
    private final FixedConfig config = new FixedConfig();
    private final FakeSkillRepository skills = new FakeSkillRepository();
    private final FakeReportRepository reports = new FakeReportRepository();
    private final FakeObservationRepository observations = new FakeObservationRepository();
    private final AnalysisModelClient enabledModel = model(true);
    private final AnalysisModelClient disabledModel = model(false);

    private SkillQualityServiceImpl service() {
        return service(enabledModel);
    }

    private SkillQualityServiceImpl service(AnalysisModelClient client) {
        return new SkillQualityServiceImpl(
                skills, reports, observations,
                new SkillStaticChecker(15, 5),
                new SkillQualityScorer(client, mapper),
                new SkillEffectAnalyzer(5, 20000),
                new SkillChainAnalyzer(client, mapper, config),
                new SkillOverallEvaluator(config),
                config);
    }

    private AnalysisModelClient model(final boolean enabled) {
        return new AnalysisModelClient(
                mapper, enabled, enabled ? "http://model.example" : "", "key",
                "model", "emb", 100, 100, 1000) {
            @Override
            public Optional<String> chat(String slug, String system, String user) {
                // 关闭态模拟真实客户端（未配置端点/预算受限 → Optional.empty）
                return enabled ? Optional.of(SCORER_JSON) : Optional.<String>empty();
            }
        };
    }

    // ------------------------------------------------------------- cases

    @Test
    void degradesToContractShapeWhenNoObservationData() {
        Map<String, Object> report = service().qualityReport("demo", null);

        assertEquals("ok", report.get("status"));
        assertEquals("1.0.0", report.get("skillVersion"));
        assertNotNull(report.get("analyzedAt"));
        assertEquals("watch", report.get("conclusion"));
        assertNotNull(report.get("overallScore"));

        @SuppressWarnings("unchecked")
        Map<String, Object> effect = (Map<String, Object>) report.get("effect");
        assertTrue(Boolean.TRUE.equals(effect.get("sampleInsufficient")));
        assertTrue(effect.containsKey("score"));
        assertNull(effect.get("score"));
        assertTrue(effect.containsKey("completionRate"));
        assertNull(effect.get("completionRate"));
        assertTrue(effect.containsKey("errorRate"));
        assertNull(effect.get("errorRate"));
        assertEquals("unknown", effect.get("cost"));
        assertEquals("暂无链路数据", asList(effect.get("problems")).get(0));
        assertEquals("上传观测数据后重新分析", asList(effect.get("fixes")).get(0));
        assertTrue(asList(effect.get("suggestions")).isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> fileCheck = (Map<String, Object>) report.get("fileCheck");
        assertEquals("pass", fileCheck.get("status"));
        assertEquals(100, ((Number) fileCheck.get("score")).intValue());
        assertEquals(5, asList(fileCheck.get("dimensions")).size());

        @SuppressWarnings("unchecked")
        Map<String, Object> wording = (Map<String, Object>) report.get("wording");
        // 读/组装时按维度扣分同源重算：100 − Σ扣分 加权 = 89（对齐 kmplm ensureIssueDeductions）
        assertEquals(89, ((Number) wording.get("score")).intValue());
        assertEquals(8, asList(wording.get("dimensions")).size());
        assertTrue(asList(report.get("suggestions")).size() >= 1);

        // 分析即落库
        assertEquals(1, reports.insertCount);
        assertEquals("watch", reports.insertedConclusion);
    }

    @Test
    void readsStoredReportBackAsFullContract() {
        Timestamp createdAt = new Timestamp(1700000000000L);
        reports.latestRow = storedRow(
                "{\"score\":80,\"status\":\"pass\",\"errorCount\":0,\"warningCount\":0,"
                        + "\"items\":[],\"problems\":[],\"fixes\":[],\"suggestions\":[]}",
                "{\"score\":80,\"topGap\":\"x\",\"dimensions\":[],\"problems\":[],"
                        + "\"fixes\":[],\"suggestions\":[]}",
                "{\"score\":60,\"cost\":\"normal\",\"completionRate\":0.9,\"errorRate\":0.05,"
                        + "\"commonIssues\":[],\"problems\":[],\"fixes\":[],\"suggestions\":[]}",
                createdAt);

        Map<String, Object> report = service().qualityReport("demo", null);

        assertEquals("ok", report.get("status"));
        assertEquals(createdAt.toInstant().toString(), report.get("analyzedAt"));
        // 0.2×80 + 0.4×80 + 0.4×60 = 72
        assertEquals(72, ((Number) report.get("overallScore")).intValue());
        assertEquals("watch", report.get("conclusion"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fileCheck = (Map<String, Object>) report.get("fileCheck");
        assertEquals(80, ((Number) fileCheck.get("score")).intValue());
        assertEquals("pass", fileCheck.get("status"));
        assertEquals(5, asList(fileCheck.get("dimensions")).size());

        @SuppressWarnings("unchecked")
        Map<String, Object> wording = (Map<String, Object>) report.get("wording");
        assertEquals(80, ((Number) wording.get("score")).intValue());
        assertEquals(8, asList(wording.get("dimensions")).size());

        @SuppressWarnings("unchecked")
        Map<String, Object> effect = (Map<String, Object>) report.get("effect");
        assertEquals(60, ((Number) effect.get("score")).intValue());
        assertEquals(0.9d, ((Number) effect.get("completionRate")).doubleValue(), 0.0001d);

        // 读路径不重写库存
        assertEquals(0, reports.insertCount);
    }

    @Test
    void summaryMapsVersionScoresAndIsoTimestamp() {
        Timestamp createdAt = new Timestamp(1700000100000L);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("version", "1.0.0");
        row.put("analyzed_at", createdAt);
        row.put("overall_score", new java.math.BigDecimal("72.00"));
        row.put("file_score", new java.math.BigDecimal("80"));
        row.put("wording_score", new java.math.BigDecimal("80"));
        row.put("effect_score", null);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row);
        reports.summaryRows = rows;

        List<Map<String, Object>> summaries = service().qualitySummary("demo");

        assertEquals(1, summaries.size());
        Map<String, Object> summary = summaries.get(0);
        assertEquals("1.0.0", summary.get("version"));
        assertEquals(createdAt.toInstant().toString(), summary.get("analyzedAt"));
        assertEquals(72, ((Number) summary.get("overallScore")).intValue());
        assertEquals(80, ((Number) summary.get("fileScore")).intValue());
        assertTrue(summary.containsKey("effectScore"));
        assertNull(summary.get("effectScore"));
    }

    @Test
    void appliesConfigWeightsAndThresholdsToOverall() {
        config.w1 = 1d;
        config.w2 = 0d;
        config.w3 = 0d;
        config.watchUpper = 70;
        Timestamp createdAt = new Timestamp(1700000000000L);
        reports.latestRow = storedRow(
                "{\"score\":77,\"status\":\"pass\",\"errorCount\":0,\"warningCount\":0}",
                "{\"score\":80}",
                "{\"score\":60}",
                createdAt);

        Map<String, Object> report = service().qualityReport("demo", null);

        // 权重只认 w1=1 → 综合分 = L1 = 77（默认 0.2/0.4/0.4 时为 79）
        assertEquals(77, ((Number) report.get("overallScore")).intValue());
        // watchUpper=70 → 77 ≥ 70 → keep（默认 85 时为 watch）
        assertEquals("keep", report.get("conclusion"));
    }

    @Test
    void staticScopePersistsReportThatReadsBack() {
        service().analyzeStaticOnly("demo", "1.0.0");

        assertEquals(1, reports.insertCount);
        assertTrue(reports.insertedL1.contains("名称与说明"));
        assertTrue(reports.insertedL1.contains("issueRows"));
        assertTrue(reports.insertedL2.contains("frontmatter".toLowerCase())
                || reports.insertedL2.contains("工作流清晰度"));
        assertNotNull(reports.insertedOverall);
        assertNotNull(reports.insertedMethod);
        assertTrue(reports.insertedMethod.contains("threshold=70/85"));

        Map<String, Object> report = service().qualityReport("demo", "1.0.0");
        assertEquals("ok", report.get("status"));
        assertEquals("1.0.0", report.get("skillVersion"));
        // 静态分析后已落库 → 不再重复插入
        assertEquals(1, reports.insertCount);
    }

    @Test
    void modelUnavailableDegradesWordingButKeepsChainRuleResult() {
        observations.withObservationData();

        Map<String, Object> report = service(disabledModel).qualityReport("demo", null);

        assertEquals("ok", report.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> wording = (Map<String, Object>) report.get("wording");
        assertTrue(Boolean.TRUE.equals(wording.get("unavailable")));
        assertEquals(0, ((Number) wording.get("score")).intValue());
        assertEquals(8, asList(wording.get("dimensions")).size());

        @SuppressWarnings("unchecked")
        Map<String, Object> effect = (Map<String, Object>) report.get("effect");
        assertNotNull(effect.get("score"));
        assertTrue(((Number) effect.get("score")).intValue() > 0);
        assertEquals(6, asList(effect.get("dimensions")).size());
        assertEquals("rule", effect.get("chainSource"));
        assertFalse("no_data".equals(report.get("conclusion")));
    }

    @Test
    void resolutionFailuresCarryStructuredCodes() {
        assertThrows(SkillApiException.class, () -> service().qualityReport("missing", null));
        SkillApiException versionMissing = assertThrows(SkillApiException.class,
                () -> service().qualityReport("demo", "2.0.0"));
        assertEquals(SkillApiException.CODE_VERSION_NOT_FOUND, versionMissing.getCode());
        SkillApiException invalid = assertThrows(SkillApiException.class,
                () -> service().qualityReport("demo", "not-a-version"));
        assertEquals(SkillApiException.CODE_VERSION_INVALID, invalid.getCode());
        assertThrows(SkillApiException.class, () -> service().qualitySummary("missing"));
    }

    @Test
    void refreshChainAnalysesSkipsFailingSkills() {
        List<Map<String, Object>> observed = new ArrayList<Map<String, Object>>();
        Map<String, Object> good = new LinkedHashMap<String, Object>();
        good.put("slug", "demo");
        observed.add(good);
        Map<String, Object> bad = new LinkedHashMap<String, Object>();
        bad.put("slug", "missing");
        observed.add(bad);
        observations.observedSkills = observed;

        int count = service().refreshAllChainAnalyses();

        assertEquals(1, count);
        assertEquals(1, reports.insertCount);
    }

    // ------------------------------------------------------------- helpers

    private static List<?> asList(Object value) {
        return value instanceof List ? (List<?>) value : new ArrayList<Object>();
    }

    private Map<String, Object> storedRow(String l1, String l2, String l3, Timestamp createdAt) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Long.valueOf(1L));
        row.put("skill_id", Long.valueOf(1L));
        row.put("version_id", Long.valueOf(11L));
        row.put("l1_json", l1);
        row.put("l2_json", l2);
        row.put("l3_json", l3);
        row.put("created_at", createdAt);
        return row;
    }

    // ------------------------------------------------------------- doubles

    private static final class FixedConfig extends SkillQualityConfigService {
        double w1 = 0.2d;
        double w2 = 0.4d;
        double w3 = 0.4d;
        int optimizeThreshold = 70;
        int watchUpper = 85;

        private FixedConfig() {
            super(null, null);
        }

        @Override public double w1() { return w1; }
        @Override public double w2() { return w2; }
        @Override public double w3() { return w3; }
        @Override public int optimizeThreshold() { return optimizeThreshold; }
        @Override public int watchUpper() { return watchUpper; }
        @Override public String ruleVersion() { return "v1"; }
        @Override public boolean chainEnabled() { return true; }
    }

    private static final class FakeSkillRepository extends SkillRepository {
        private FakeSkillRepository() {
            super(new JdbcTemplate());
        }

        @Override
        public Map<String, Object> detail(String slug) {
            if (slug == null || "missing".equals(slug)) {
                throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
            }
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("id", Long.valueOf(1L));
            detail.put("slug", slug);
            detail.put("version_label", "1.0.0");
            return detail;
        }

        @Override
        public Long versionId(String slug, String version) {
            if (version != null && !version.isEmpty() && !"1.0.0".equals(version)) {
                throw new SkillApiException("Skill版本不存在", SkillApiException.CODE_VERSION_NOT_FOUND);
            }
            return Long.valueOf(11L);
        }
    }

    private static final class FakeReportRepository extends SkillQualityReportRepository {
        private Map<String, Object> latestRow;
        private List<Map<String, Object>> summaryRows = new ArrayList<Map<String, Object>>();
        private List<Map<String, Object>> fileTexts = new ArrayList<Map<String, Object>>();
        private int insertCount;
        private String insertedL1;
        private String insertedL2;
        private Double insertedOverall;
        private String insertedConclusion;
        private String insertedMethod;

        private FakeReportRepository() {
            super(new JdbcTemplate());
            Map<String, Object> skillMd = new LinkedHashMap<String, Object>();
            skillMd.put("path", "SKILL.md");
            skillMd.put("content", GOOD_SKILL_MD);
            fileTexts.add(skillMd);
            Map<String, Object> script = new LinkedHashMap<String, Object>();
            script.put("path", "scripts/run.py");
            script.put("content", "print('x')");
            fileTexts.add(script);
        }

        @Override
        public Map<String, Object> latest(Long skillId, Long versionId) {
            return latestRow;
        }

        @Override
        public Long insert(Long skillId, Long versionId, String l1Json, String l2Json,
                           String l3Json, Double overallScore, String conclusion,
                           String methodNote, String ruleVersion) {
            insertCount++;
            insertedL1 = l1Json;
            insertedL2 = l2Json;
            insertedOverall = overallScore;
            insertedConclusion = conclusion;
            insertedMethod = methodNote;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", Long.valueOf(insertCount));
            row.put("l1_json", l1Json);
            row.put("l2_json", l2Json);
            row.put("l3_json", l3Json);
            row.put("created_at", new Timestamp(System.currentTimeMillis()));
            latestRow = row;
            return Long.valueOf(insertCount);
        }

        @Override
        public List<Map<String, Object>> summaryRows(Long skillId) {
            return summaryRows;
        }

        @Override
        public String versionLabel(Long versionId) {
            return "1.0.0";
        }

        @Override
        public List<Map<String, Object>> skillFileTexts(Long versionId) {
            return fileTexts;
        }
    }

    private static final class FakeObservationRepository extends ObservationRepository {
        private Map<String, Object> quality = new LinkedHashMap<String, Object>();
        private Map<String, Object> usage = new LinkedHashMap<String, Object>();
        private List<Map<String, Object>> observedSkills = new ArrayList<Map<String, Object>>();

        private FakeObservationRepository() {
            super(new JdbcTemplate(), new ObjectMapper());
            quality.put("calls", Integer.valueOf(0));
            quality.put("skillTurns", Integer.valueOf(0));
            quality.put("sessions", Integer.valueOf(0));
            quality.put("errors", Integer.valueOf(0));
            quality.put("reloadTurns", Integer.valueOf(0));
            quality.put("errorRate", Double.valueOf(0d));
            quality.put("reloadRate", Double.valueOf(0d));
            quality.put("checklistCoverage", Double.valueOf(-1d));
            usage.put("totalTokens", Long.valueOf(0L));
            usage.put("requestCount", Integer.valueOf(0));
        }

        void withObservationData() {
            quality.put("calls", Integer.valueOf(10));
            quality.put("skillTurns", Integer.valueOf(8));
            quality.put("sessions", Integer.valueOf(3));
            quality.put("errors", Integer.valueOf(1));
            quality.put("reloadTurns", Integer.valueOf(0));
            quality.put("errorRate", Double.valueOf(0.1d));
            quality.put("reloadRate", Double.valueOf(0d));
            quality.put("checklistCoverage", Double.valueOf(0.9d));
            usage.put("totalTokens", Long.valueOf(5000L));
            usage.put("requestCount", Integer.valueOf(10));
        }

        @Override
        public Map<String, Object> skillQualitySummary(String slug, String versionFilter) {
            return quality;
        }

        @Override
        public Map<String, Object> skillTokenSummary(String slug, String versionFilter) {
            return usage;
        }

        @Override
        public List<Map<String, Object>> listObservedSkills() {
            return observedSkills;
        }
    }
}
