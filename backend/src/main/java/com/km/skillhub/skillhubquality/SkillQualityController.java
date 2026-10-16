package com.km.skillhub.skillhubquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.skill.SkillApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 质量报告 REST（挂观测域 {@code /api/observations}，契约对齐
 * {@code frontend/src/types/skillQuality.ts}）。
 *
 * <p>GET 报告 / GET 汇总 / POST 触发分析（scope=static|chain|all）。
 * 错误时返回结构体 + {@code status}（404=not_found，400=error），字段含
 * {@code message, code} 与报告降级骨架（overallScore=null、conclusion=no_data）。
 * 响应以独立 ObjectMapper 序列化，保留契约中的 null 字段（不受全局
 * {@code jackson.default-property-inclusion: non_null} 影响）。</p>
 */
@RestController
@RequestMapping("/api/observations")
public class SkillQualityController {

    private final SkillQualityService qualityService;
    /** 默认包含 null 的序列化器（报告契约要求 null 字段可见）。 */
    private final ObjectMapper responseMapper = new ObjectMapper();

    public SkillQualityController(SkillQualityService qualityService) {
        this.qualityService = qualityService;
    }

    @GetMapping("/skills/{slug}/quality-report")
    public ResponseEntity<String> qualityReport(@PathVariable String slug,
                                                @RequestParam(required = false) String version) {
        try {
            return json(HttpStatus.OK, qualityService.qualityReport(slug, version));
        } catch (SkillApiException ex) {
            return error(ex);
        }
    }

    @GetMapping("/skills/{slug}/quality-report/summary")
    public ResponseEntity<String> qualityReportSummary(@PathVariable String slug) {
        try {
            return json(HttpStatus.OK, qualityService.qualitySummary(slug));
        } catch (SkillApiException ex) {
            return error(ex);
        }
    }

    @PostMapping("/skills/{slug}/quality-report/analyze")
    public ResponseEntity<String> analyze(@PathVariable String slug,
                                          @RequestBody(required = false) Map<String, Object> body) {
        String version = body == null ? null : asText(body.get("version"));
        String scope = body == null ? null : asText(body.get("scope"));
        try {
            Map<String, Object> report;
            if ("static".equalsIgnoreCase(scope)) {
                // 回填/修复：只重算格式检查 + skill内容检查
                qualityService.analyzeStaticOnly(slug, version);
                report = qualityService.qualityReport(slug, version);
            } else if ("all".equalsIgnoreCase(scope)) {
                // 静态 + 链路全量重算
                qualityService.analyzeStaticOnly(slug, version);
                report = qualityService.analyzeChainForVersion(slug, version);
            } else {
                // 默认只刷新 skill链路分析；格式/内容在无报告时兜底计算
                report = qualityService.analyzeChainForVersion(slug, version);
            }
            Map<String, Object> ack = new LinkedHashMap<String, Object>();
            ack.put("accepted", Boolean.TRUE);
            ack.put("message", "分析完成");
            ack.put("report", report);
            return json(HttpStatus.OK, ack);
        } catch (SkillApiException ex) {
            return error(ex);
        }
    }

    // ------------------------------------------------------------- helpers

    private ResponseEntity<String> error(SkillApiException ex) {
        HttpStatus status = statusOf(ex);
        Map<String, Object> body = errorBody(ex.getMessage(), ex.getCode());
        body.put("status", status == HttpStatus.NOT_FOUND ? "not_found" : "error");
        return json(status, body);
    }

    private static HttpStatus statusOf(SkillApiException ex) {
        String code = ex.getCode();
        return SkillApiException.CODE_SKILL_NOT_FOUND.equals(code)
                || SkillApiException.CODE_VERSION_NOT_FOUND.equals(code)
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
    }

    /** 与 kmplm errorBody 对齐的报告降级骨架（含 message/code/status）。 */
    private static Map<String, Object> errorBody(String message, String code) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", message == null ? "" : message);
        body.put("code", code == null ? SkillApiException.CODE_VALIDATION_ERROR : code);
        body.put("overallScore", null);
        body.put("conclusion", "no_data");
        body.put("fileCheck", Collections.emptyMap());
        body.put("wording", Collections.emptyMap());
        body.put("effect", Collections.emptyMap());
        body.put("suggestions", Collections.emptyList());
        return body;
    }

    private ResponseEntity<String> json(HttpStatus status, Object body) {
        try {
            String text = responseMapper.writeValueAsString(body);
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(text);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"响应序列化失败\",\"code\":\"VALIDATION_ERROR\"}");
        }
    }

    private static String asText(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        return text;
    }
}
