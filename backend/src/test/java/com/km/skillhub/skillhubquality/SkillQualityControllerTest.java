package com.km.skillhub.skillhubquality;

import com.km.skillhub.skill.SkillApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三端点契约：路由（version/scope）、错误结构 {message, code} + status
 * （404=not_found / 400=error）与降级骨架、analyze ack 形状、
 * 响应保留 null 字段。
 */
class SkillQualityControllerTest {

    /** 记录路由参数的服务替身。 */
    private static final class RecordingService implements SkillQualityService {
        String lastSlug;
        String lastVersion;
        String lastScope;
        boolean staticCalled;
        boolean chainCalled;

        @Override
        public Map<String, Object> qualityReport(String slug, String version) {
            lastSlug = slug;
            lastVersion = version;
            if ("missing".equals(slug)) {
                throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
            }
            if ("version-missing".equals(slug)) {
                throw new SkillApiException("Skill版本不存在", SkillApiException.CODE_VERSION_NOT_FOUND);
            }
            if ("bad".equals(slug)) {
                throw new SkillApiException("slug 不能为空", SkillApiException.CODE_VALIDATION_ERROR);
            }
            Map<String, Object> report = new LinkedHashMap<String, Object>();
            report.put("overallScore", null);
            report.put("conclusion", "no_data");
            report.put("status", "ok");
            return report;
        }

        @Override
        public List<Map<String, Object>> qualitySummary(String slug) {
            if ("missing".equals(slug)) {
                throw new SkillApiException("Skill不存在", SkillApiException.CODE_SKILL_NOT_FOUND);
            }
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("version", "1.0.0");
            row.put("overallScore", Integer.valueOf(72));
            rows.add(row);
            return rows;
        }

        @Override
        public void analyzeStaticOnly(String slug, String version) {
            staticCalled = true;
            lastVersion = version;
        }

        @Override
        public Map<String, Object> analyzeChainForVersion(String slug, String version) {
            chainCalled = true;
            lastVersion = version;
            return qualityReport(slug, version);
        }

        @Override
        public int refreshAllChainAnalyses() {
            return 0;
        }
    }

    private final RecordingService service = new RecordingService();
    private final SkillQualityController controller = new SkillQualityController(service);

    @Test
    void reportKeepsNullFieldsAndStatus() {
        ResponseEntity<String> response = controller.qualityReport("demo", "1.0.0");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertTrue(body.contains("\"status\":\"ok\""));
        // null 字段不被全局 non_null 配置吃掉
        assertTrue(body.contains("\"overallScore\":null"));
        assertTrue(body.contains("\"conclusion\":\"no_data\""));
        assertEquals("demo", service.lastSlug);
        assertEquals("1.0.0", service.lastVersion);
    }

    @Test
    void notFoundErrorsMapTo404WithDegradedSkeleton() {
        ResponseEntity<String> missing = controller.qualityReport("missing", null);

        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        String body = missing.getBody();
        assertTrue(body.contains("\"code\":\"SKILL_NOT_FOUND\""));
        assertTrue(body.contains("\"message\""));
        assertTrue(body.contains("\"status\":\"not_found\""));
        assertTrue(body.contains("\"conclusion\":\"no_data\""));
        assertTrue(body.contains("\"overallScore\":null"));

        ResponseEntity<String> versionMissing = controller.qualityReport("version-missing", null);
        assertEquals(HttpStatus.NOT_FOUND, versionMissing.getStatusCode());
        assertTrue(versionMissing.getBody().contains("VERSION_NOT_FOUND"));
    }

    @Test
    void validationErrorsMapTo400() {
        ResponseEntity<String> response = controller.qualityReport("bad", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("\"code\":\"VALIDATION_ERROR\""));
        assertTrue(response.getBody().contains("\"status\":\"error\""));
    }

    @Test
    void summaryReturnsListAndPropagatesErrorShape() {
        ResponseEntity<String> ok = controller.qualityReportSummary("demo");
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertTrue(ok.getBody().startsWith("["));
        assertTrue(ok.getBody().contains("\"version\":\"1.0.0\""));

        ResponseEntity<String> missing = controller.qualityReportSummary("missing");
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertTrue(missing.getBody().contains("SKILL_NOT_FOUND"));
    }

    @Test
    void analyzeScopeStaticRunsStaticThenReport() {
        ResponseEntity<String> response = controller.analyze("demo", body("1.2.3", "static"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(service.staticCalled);
        assertEquals(false, service.chainCalled);
        assertEquals("1.2.3", service.lastVersion);
        String body = response.getBody();
        assertTrue(body.contains("\"accepted\":true"));
        assertTrue(body.contains("\"message\":\"分析完成\""));
        assertTrue(body.contains("\"report\""));
    }

    @Test
    void analyzeScopeChainAndAllRouteAsDocumented() {
        controller.analyze("demo", body(null, "chain"));
        assertTrue(service.chainCalled);
        assertEquals(false, service.staticCalled);
        assertNull(service.lastVersion);

        service.staticCalled = false;
        service.chainCalled = false;
        controller.analyze("demo", body(null, "all"));
        assertTrue(service.staticCalled);
        assertTrue(service.chainCalled);

        // 缺省 scope = chain
        service.staticCalled = false;
        service.chainCalled = false;
        controller.analyze("demo", null);
        assertTrue(service.chainCalled);
        assertEquals(false, service.staticCalled);
    }

    @Test
    void analyzeErrorReturnsStructuredBody() {
        ResponseEntity<String> response = controller.analyze("missing", body(null, "chain"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody().contains("SKILL_NOT_FOUND"));
        assertTrue(response.getBody().contains("\"status\":\"not_found\""));
    }

    private static Map<String, Object> body(String version, String scope) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (version != null) body.put("version", version);
        if (scope != null) body.put("scope", scope);
        return body;
    }
}
