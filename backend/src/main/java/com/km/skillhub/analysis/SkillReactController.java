package com.km.skillhub.analysis;

import com.km.skillhub.skill.SkillApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct trajectory REST under /api/observations.
 *
 * <p>GET /optimize-tasks/{id}/react-steps returns steps
 * {@code [{stepNo, thought, actionType, actionJson, observationJson, durationMs, createdAt}]};
 * {@code ?after=} is the exclusive stepNo cursor; {@code ?size=} defaults to 500 and caps at 2000.
 * Missing task returns 404.</p>
 */
@RestController
@RequestMapping("/api/observations")
public class SkillReactController {

    private static final int DEFAULT_PAGE_SIZE = 500;
    private static final int MAX_PAGE_SIZE = 2000;

    private final SkillOptimizeRepository repository;

    public SkillReactController(SkillOptimizeRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/optimize-tasks/{id}/react-steps")
    public ResponseEntity<Object> reactSteps(@PathVariable Long id,
                                             @RequestParam(required = false) Long after,
                                             @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        SkillOptimizeTask task = repository.findTask(id);
        if (task == null) {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("message", "优化任务不存在：" + id);
            body.put("code", SkillOptimizeServiceImpl.CODE_OPTIMIZE_TASK_NOT_FOUND);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        int limit = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        List<SkillReactStep> rows = repository.listReactSteps(id, after, limit);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (SkillReactStep row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("stepNo", row.getStepNo());
            item.put("thought", row.getThought());
            item.put("actionType", row.getActionType());
            item.put("actionJson", row.getActionJson());
            item.put("observationJson", row.getObservationJson());
            item.put("durationMs", row.getDurationMs());
            item.put("createdAt", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
            items.add(item);
        }
        return ResponseEntity.ok(items);
    }
}
