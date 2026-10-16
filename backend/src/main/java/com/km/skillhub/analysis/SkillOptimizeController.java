package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.skill.SkillApiException;
import com.km.skillhub.skill.SkillRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill optimize task / candidate REST under /api/observations.
 * Contract matches frontend types skillOptimize.ts + skillWorkbench.ts (T20).
 */
@RestController
@RequestMapping("/api/observations")
public class SkillOptimizeController {

    private final SkillOptimizeService optimizeService;
    private final SkillRepository skillRepository;
    private final ObjectMapper objectMapper;

    public SkillOptimizeController(SkillOptimizeService optimizeService,
                                   SkillRepository skillRepository,
                                   ObjectMapper objectMapper) {
        this.optimizeService = optimizeService;
        this.skillRepository = skillRepository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @GetMapping("/skills/{slug}/optimize-tasks")
    public ResponseEntity<Object> listTasks(@PathVariable String slug,
                                            @RequestParam(required = false) String mode,
                                            @RequestParam(required = false) String version) {
        try {
            Map<String, Object> detail = skillRepository.detail(slug);
            Long skillId = asLong(detail.get("id"));
            // Align with kmplm: accept version (validates existence) and filter by it when given.
            Long versionIdFilter = null;
            if (version != null && !version.trim().isEmpty()) {
                versionIdFilter = skillRepository.versionId(slug, version);
            }
            List<SkillOptimizeTask> tasks = optimizeService.listTasks(skillId, mode);
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (SkillOptimizeTask task : tasks) {
                if (versionIdFilter != null && !versionIdFilter.equals(task.getVersionId())) {
                    continue;
                }
                items.add(taskView(task, true));
            }
            return ResponseEntity.ok(items);
        } catch (SkillApiException ex) {
            return errorFor(ex);
        }
    }

    @PostMapping("/skills/{slug}/optimize-tasks")
    public ResponseEntity<Object> createTask(@PathVariable String slug,
                                             @RequestBody(required = false) Map<String, Object> body) {
        try {
            String version = asText(body == null ? null : body.get("version"));
            Map<String, Object> detail = skillRepository.detail(slug);
            Long skillId = asLong(detail.get("id"));
            Long versionId = skillRepository.versionId(slug, version);
            String mode = asText(body == null ? null : body.get("mode"));
            List<String> priorities = stringList(body == null ? null : body.get("priorities"));
            String createdBy = asText(body == null ? null : body.get("createdBy"));
            List<String> targetIssueIds = stringList(body == null ? null : body.get("targetIssueIds"));
            String runMode = asText(body == null ? null : body.get("runMode"));
            Long conversationId = asLong(body == null ? null : body.get("conversationId"));
            SkillOptimizeTask task = optimizeService.createTask(
                    skillId, versionId, mode, priorities, createdBy,
                    runMode, targetIssueIds, conversationId);
            return ResponseEntity.ok(taskView(task, false));
        } catch (SkillApiException ex) {
            return errorFor(ex);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorBody(ex.getMessage(), SkillApiException.CODE_VALIDATION_ERROR));
        }
    }

    @PostMapping("/optimize-tasks/{id}/cancel")
    public ResponseEntity<Object> cancelTask(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, Object> body) {
        try {
            String operator = asText(body == null ? null : body.get("operator"));
            String reason = asText(body == null ? null : body.get("reason"));
            SkillOptimizeTask task = optimizeService.cancelTask(id, operator, reason);
            return ResponseEntity.ok(taskView(task, false));
        } catch (SkillApiException ex) {
            return errorFor(ex);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorBody(ex.getMessage(), SkillApiException.CODE_VALIDATION_ERROR));
        }
    }

    @GetMapping("/optimize-candidates/{id}")
    public ResponseEntity<Object> candidate(@PathVariable Long id) {
        try {
            SkillOptimizeCandidate found = optimizeService.getCandidate(id);
            return ResponseEntity.ok(candidateView(found));
        } catch (SkillApiException ex) {
            return errorFor(ex);
        }
    }

    @PostMapping("/optimize-candidates/{id}/decision")
    public ResponseEntity<Object> decide(@PathVariable Long id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        try {
            String decision = asText(body == null ? null : body.get("decision"));
            String reason = asText(body == null ? null : body.get("reason"));
            String operator = asText(body == null ? null : body.get("operator"));
            SkillOptimizeCandidate updated = optimizeService.decide(id, decision, operator, reason);
            return ResponseEntity.ok(candidateView(updated));
        } catch (SkillApiException ex) {
            return errorFor(ex);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorBody(ex.getMessage(), SkillApiException.CODE_VALIDATION_ERROR));
        }
    }

    // ------------------------------------------------------------- views

    Map<String, Object> taskView(SkillOptimizeTask task, boolean withCandidates) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        if (task == null) {
            return view;
        }
        view.put("id", task.getId());
        view.put("skillId", task.getSkillId());
        view.put("versionId", task.getVersionId());
        view.put("mode", task.getMode());
        view.put("runMode", task.getRunMode());
        view.put("targetIssueIds", parseStringList(task.getTargetIssueIdsJson()));
        view.put("targetScope", parseJson(task.getTargetScopeJson()));
        view.put("conversationId", task.getConversationId());
        view.put("status", task.getStatus());
        view.put("progressStage", task.getProgressStage() == null || task.getProgressStage().trim().isEmpty()
                ? defaultProgressStage(task.getStatus()) : task.getProgressStage());
        view.put("progressPercent", task.getProgressPercent() == null
                ? Integer.valueOf(defaultProgressPercent(task.getStatus())) : task.getProgressPercent());
        view.put("progressMessage", task.getProgressMessage() == null
                ? defaultProgressMessage(task.getStatus()) : task.getProgressMessage());
        view.put("priorityList", parseStringList(task.getPriorityListJson()));
        view.put("failReason", task.getFailReason());
        view.put("createdBy", task.getCreatedBy());
        view.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
        view.put("updatedAt", task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString());
        if (withCandidates) {
            List<SkillOptimizeCandidate> candidates = optimizeService.listCandidates(task.getId());
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (SkillOptimizeCandidate candidate : candidates) {
                items.add(candidateView(candidate));
            }
            view.put("candidates", items);
        }
        return view;
    }

    Map<String, Object> candidateView(SkillOptimizeCandidate candidate) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        if (candidate == null) {
            return view;
        }
        view.put("id", candidate.getId());
        view.put("taskId", candidate.getTaskId());
        view.put("summary", candidate.getSummary());
        view.put("diffText", candidate.getDiffText());
        view.put("edits", parseJson(candidate.getEditsJson()));
        view.put("gates", parseJson(candidate.getGatesJson()));
        view.put("paired", parseJson(candidate.getPairedJson()));
        view.put("coverage", parseJson(candidate.getCoverageJson()));
        view.put("decision", candidate.getDecision());
        view.put("decisionReason", candidate.getDecisionReason());
        view.put("decidedBy", candidate.getDecidedBy());
        view.put("decidedAt", candidate.getDecidedAt() == null ? null : candidate.getDecidedAt().toString());
        view.put("resultVersionId", candidate.getResultVersionId());
        view.put("resultVersionLabel", candidate.getResultVersionLabel());
        view.put("createdAt", candidate.getCreatedAt() == null ? null : candidate.getCreatedAt().toString());
        view.put("updatedAt", candidate.getUpdatedAt() == null ? null : candidate.getUpdatedAt().toString());
        return view;
    }

    private String defaultProgressStage(String status) {
        if (SkillOptimizeTask.STATUS_PENDING_REVIEW.equals(status)) return SkillOptimizeTask.STAGE_PENDING_REVIEW;
        if (SkillOptimizeTask.STATUS_GATED_FAIL.equals(status) || SkillOptimizeTask.STATUS_REJECTED.equals(status)) {
            return SkillOptimizeTask.STAGE_FAILED;
        }
        if (SkillOptimizeTask.STATUS_RUNNING.equals(status)) return SkillOptimizeTask.STAGE_GENERATING_CANDIDATE;
        return SkillOptimizeTask.STAGE_QUEUED;
    }

    private int defaultProgressPercent(String status) {
        if (SkillOptimizeTask.STATUS_PENDING_REVIEW.equals(status)
                || SkillOptimizeTask.STATUS_GATED_FAIL.equals(status)
                || SkillOptimizeTask.STATUS_REJECTED.equals(status)) return 100;
        if (SkillOptimizeTask.STATUS_RUNNING.equals(status)) return 30;
        return 0;
    }

    private String defaultProgressMessage(String status) {
        if (SkillOptimizeTask.STATUS_PENDING_REVIEW.equals(status)) return "五项检查已完成，等待人工确认";
        if (SkillOptimizeTask.STATUS_GATED_FAIL.equals(status) || SkillOptimizeTask.STATUS_REJECTED.equals(status)) {
            return "优化未通过，请查看失败原因";
        }
        if (SkillOptimizeTask.STATUS_RUNNING.equals(status)) return "正在生成候选改动";
        if (SkillOptimizeTask.STATUS_WAITING_INPUT.equals(status)) return "等待用户确认继续";
        return "等待后台执行";
    }

    // ------------------------------------------------------------- helpers

    private ResponseEntity<Object> errorFor(SkillApiException ex) {
        boolean notFound = SkillApiException.CODE_SKILL_NOT_FOUND.equals(ex.getCode())
                || SkillApiException.CODE_VERSION_NOT_FOUND.equals(ex.getCode())
                || SkillOptimizeServiceImpl.CODE_OPTIMIZE_TASK_NOT_FOUND.equals(ex.getCode())
                || SkillOptimizeServiceImpl.CODE_CANDIDATE_NOT_FOUND.equals(ex.getCode());
        HttpStatus status = notFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(errorBody(ex.getMessage(), ex.getCode()));
    }

    private Map<String, Object> errorBody(String message, String code) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", message == null ? "" : message);
        body.put("code", code == null ? SkillApiException.CODE_VALIDATION_ERROR : code);
        return body;
    }

    private Object parseJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    private List<String> parseStringList(String json) {
        Object parsed = parseJson(json);
        if (!(parsed instanceof List)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) parsed) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("conversationId 必须是数字，收到：" + value);
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List)) {
            return null;
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) value) {
            String text = asText(item);
            if (text != null) {
                result.add(text);
            }
        }
        return result.isEmpty() ? null : result;
    }
}
