package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.skill.SkillApiException;
import com.km.skillhub.skill.SkillPackageParser;
import com.km.skillhub.skill.SkillVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optimize task orchestration: status machine + five gates + adopt/reject.
 *
 * <p>Gate thresholds default to skill_quality_config values when present
 * (minImprovement / maxRegression / editBudget), otherwise 0.02 / 0.02 / 4.</p>
 */
@Service
public class SkillOptimizeServiceImpl implements SkillOptimizeService {

    private static final Logger log = LoggerFactory.getLogger(SkillOptimizeServiceImpl.class);

    public static final String DECISION_ADOPT = "adopt";
    public static final String DECISION_REJECT = "reject";
    public static final String CODE_OPTIMIZE_CONFLICT = "OPTIMIZE_TASK_CONFLICT";
    public static final String CODE_ADOPT_FAILED = "OPTIMIZE_ADOPT_FAILED";
    public static final String CODE_OPTIMIZE_TASK_NOT_FOUND = "OPTIMIZE_TASK_NOT_FOUND";
    public static final String CODE_CANDIDATE_NOT_FOUND = "CANDIDATE_NOT_FOUND";

    private final SkillOptimizeRepository repository;
    private final ObjectMapper objectMapper;

    public SkillOptimizeServiceImpl(SkillOptimizeRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public SkillOptimizeTask createTask(Long skillId, Long versionId, String mode,
                                        List<String> priorities, String createdBy,
                                        String runMode, List<String> targetIssueIds, Long conversationId) {
        if (skillId == null) {
            throw new IllegalArgumentException("skillId 不能为空");
        }
        if (versionId == null) {
            throw new IllegalArgumentException("versionId 不能为空");
        }
        String normalizedMode = normalizeMode(mode);
        String normalizedRunMode = normalizeRunMode(runMode);
        rejectWhenActiveTaskExists(skillId, versionId);

        SkillOptimizeTask task = new SkillOptimizeTask();
        task.setSkillId(skillId);
        task.setVersionId(versionId);
        task.setMode(normalizedMode);
        task.setRunMode(normalizedRunMode);
        task.setConversationId(conversationId);
        task.setStatus(SkillOptimizeTask.STATUS_QUEUED);
        task.setProgressStage(SkillOptimizeTask.STAGE_QUEUED);
        task.setProgressPercent(Integer.valueOf(0));
        task.setProgressMessage("等待后台执行");
        task.setPriorityListJson(toJson(priorities == null ? Collections.<String>emptyList() : priorities));
        task.setTargetIssueIdsJson(toJson(targetIssueIds == null
                ? Collections.<String>emptyList() : targetIssueIds));
        task.setInputsJson("{}");
        task.setEngine("react");
        task.setCreatedBy(createdBy);
        return repository.insertTask(task);
    }

    private void rejectWhenActiveTaskExists(Long skillId, Long versionId) {
        Long active = repository.countActiveTasks(skillId, versionId);
        if (active != null && active.longValue() > 0L) {
            throw new SkillApiException(
                    "该 Skill 版本已有进行中的优化任务（queued/running/waiting_input），请等待完成或取消后再发起",
                    CODE_OPTIMIZE_CONFLICT);
        }
    }

    @Override
    public SkillOptimizeTask cancelTask(Long taskId, String operator, String reason) {
        SkillOptimizeTask task = requireTask(taskId);
        String status = task.getStatus();
        if (!SkillOptimizeTask.STATUS_QUEUED.equals(status)
                && !SkillOptimizeTask.STATUS_WAITING_INPUT.equals(status)
                && !SkillOptimizeTask.STATUS_RUNNING.equals(status)) {
            throw new SkillApiException(
                    "当前状态不允许取消（仅 queued/waiting_input/running 可取消），当前：" + status,
                    CODE_OPTIMIZE_CONFLICT);
        }
        String detail = "用户取消";
        if (reason != null && !reason.trim().isEmpty()) {
            detail = detail + "：" + reason.trim();
        }
        if (operator != null && !operator.trim().isEmpty()) {
            task.setCreatedBy(operator.trim());
        }
        task.setStatus(SkillOptimizeTask.STATUS_REJECTED);
        task.setProgressStage(SkillOptimizeTask.STAGE_FAILED);
        task.setProgressPercent(Integer.valueOf(100));
        task.setProgressMessage(detail);
        task.setFailReason(detail);
        repository.updateTask(task);
        return task;
    }

    @Override
    public SkillOptimizeTask advance(Long taskId, String note) {
        SkillOptimizeTask task = requireTask(taskId);
        if (!SkillOptimizeTask.STATUS_WAITING_INPUT.equals(task.getStatus())) {
            throw new SkillApiException(
                    "当前状态不允许推进（仅 waiting_input 可推进），当前：" + task.getStatus(),
                    CODE_OPTIMIZE_CONFLICT);
        }
        String normalizedNote = note == null ? "" : note.trim();
        if (!normalizedNote.isEmpty()) {
            task.setInputsJson(appendAdvanceNote(task.getInputsJson(), normalizedNote));
        }
        task.setStatus(SkillOptimizeTask.STATUS_RUNNING);
        repository.updateTask(task);
        return task;
    }

    @Override
    public SkillOptimizeTask findWaitingInputTask(Long conversationId) {
        return repository.findWaitingInputTask(conversationId);
    }

    private String appendAdvanceNote(String inputsJson, String note) {
        Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        if (inputsJson != null && !inputsJson.trim().isEmpty()) {
            try {
                Object parsed = objectMapper.readValue(inputsJson, Object.class);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) parsed;
                    inputs = new LinkedHashMap<String, Object>(cast);
                }
            } catch (Exception ignored) {
                // invalid inputsJson rebuilt; do not block advance
            }
        }
        Object existing = inputs.get("advanceNotes");
        List<String> notes = new ArrayList<String>();
        if (existing instanceof List) {
            for (Object item : (List<?>) existing) {
                if (item != null) {
                    notes.add(String.valueOf(item));
                }
            }
        }
        notes.add(note);
        inputs.put("advanceNotes", notes);
        return toJson(inputs);
    }

    @Override
    public SkillOptimizeTask markRunning(Long taskId) {
        SkillOptimizeTask task = requireTask(taskId);
        if (!SkillOptimizeTask.STATUS_QUEUED.equals(task.getStatus())) {
            throw new IllegalStateException("仅 queued 任务可进入 running，当前：" + task.getStatus());
        }
        task.setStatus(SkillOptimizeTask.STATUS_RUNNING);
        task.setProgressStage(SkillOptimizeTask.STAGE_READING_SKILL);
        task.setProgressPercent(Integer.valueOf(10));
        task.setProgressMessage("正在读取当前版本文件");
        repository.updateTask(task);
        return task;
    }

    @Override
    public SkillOptimizeTask failTask(Long taskId, String reason) {
        SkillOptimizeTask task = requireTask(taskId);
        if (!SkillOptimizeTask.STATUS_RUNNING.equals(task.getStatus())
                && !SkillOptimizeTask.STATUS_QUEUED.equals(task.getStatus())
                && !SkillOptimizeTask.STATUS_WAITING_INPUT.equals(task.getStatus())) {
            throw new IllegalStateException("仅 queued/running/waiting_input 任务可标记失败，当前："
                    + task.getStatus());
        }
        String detail = reason == null || reason.trim().isEmpty() ? "优化执行失败" : reason.trim();
        task.setStatus(SkillOptimizeTask.STATUS_GATED_FAIL);
        task.setProgressStage(SkillOptimizeTask.STAGE_FAILED);
        task.setProgressPercent(Integer.valueOf(100));
        task.setProgressMessage(detail);
        task.setFailReason(detail);
        repository.updateTask(task);
        return task;
    }

    @Override
    public SkillOptimizeTask updateProgress(Long taskId, String stage, int percent, String message) {
        SkillOptimizeTask task = requireTask(taskId);
        task.setProgressStage(stage == null || stage.trim().isEmpty()
                ? SkillOptimizeTask.STAGE_QUEUED : stage.trim());
        task.setProgressPercent(Integer.valueOf(Math.max(0, Math.min(percent, 100))));
        task.setProgressMessage(message == null ? "" : message.trim());
        repository.updateTask(task);
        return task;
    }

    @Override
    public SkillOptimizeTask getTask(Long taskId) {
        return requireTask(taskId);
    }

    @Override
    public List<SkillOptimizeTask> listTasks(Long skillId, String mode) {
        return repository.listTasksBySkill(skillId, mode);
    }

    @Override
    public SkillOptimizeCandidate addCandidate(Long taskId, String summary, String diffText,
                                               List<SkillOptimizeGate.Edit> edits) {
        SkillOptimizeTask task = requireTask(taskId);
        if (!SkillOptimizeTask.STATUS_RUNNING.equals(task.getStatus())
                && !SkillOptimizeTask.STATUS_QUEUED.equals(task.getStatus())
                && !SkillOptimizeTask.STATUS_GATED_FAIL.equals(task.getStatus())) {
            throw new IllegalStateException("当前状态不允许追加候选：" + task.getStatus());
        }
        List<SkillOptimizeGate.Edit> editList = edits == null
                ? Collections.<SkillOptimizeGate.Edit>emptyList() : edits;

        SkillOptimizeCandidate candidate = new SkillOptimizeCandidate();
        candidate.setTaskId(taskId);
        candidate.setSummary(summary);
        candidate.setDiffText(diffText);
        candidate.setEditsJson(toJson(editList));
        candidate.setGatesJson("{}");
        candidate.setPairedJson("{}");
        candidate.setDecision(SkillOptimizeCandidate.DECISION_PENDING);
        candidate.setTruncated(Boolean.FALSE);
        return repository.insertCandidate(candidate);
    }

    @Override
    public SkillOptimizeCandidate evaluateCandidate(Long candidateId,
                                                    SkillOptimizeGate.Metrics baseline,
                                                    SkillOptimizeGate.Metrics candidateMetrics,
                                                    SkillOptimizeGate.Evaluation evidence) {
        return evaluateCandidate(candidateId, baseline, candidateMetrics, evidence, null);
    }

    @Override
    public SkillOptimizeCandidate evaluateCandidate(Long candidateId,
                                                    SkillOptimizeGate.Metrics baseline,
                                                    SkillOptimizeGate.Metrics candidateMetrics,
                                                    SkillOptimizeGate.Evaluation evidence,
                                                    Map<String, String> evidenceTypes) {
        SkillOptimizeCandidate candidate = requireCandidate(candidateId);
        SkillOptimizeTask task = requireTask(candidate.getTaskId());

        SkillOptimizeGate gate = buildGate();
        List<SkillOptimizeGate.Edit> edits = parseEdits(candidate.getEditsJson());
        Map<String, String> evidenceMap = mergeEvidence(candidate.getEvidenceSummaryJson(), evidenceTypes);
        SkillOptimizeGate.GateReport report =
                gate.evaluate(baseline, candidateMetrics, edits, evidence, evidenceMap);

        candidate.setGatesJson(toJson(report));
        candidate.setPairedJson(toJson(pairedSummary(evidence)));
        repository.updateCandidate(candidate);

        if (report.isAllPassed()) {
            task.setStatus(SkillOptimizeTask.STATUS_PENDING_REVIEW);
            task.setProgressStage(SkillOptimizeTask.STAGE_PENDING_REVIEW);
            task.setProgressPercent(Integer.valueOf(100));
            task.setProgressMessage("五项检查已完成，等待人工确认");
            task.setFailReason(null);
        } else {
            task.setStatus(SkillOptimizeTask.STATUS_GATED_FAIL);
            task.setProgressStage(SkillOptimizeTask.STAGE_FAILED);
            task.setProgressPercent(Integer.valueOf(100));
            task.setProgressMessage("检查未通过");
            task.setFailReason("门禁未过：" + join(report.getFailedGates()));
        }
        repository.updateTask(task);
        return candidate;
    }

    /** Thresholds from skill_quality_config when readable; otherwise defaults. */
    SkillOptimizeGate buildGate() {
        Double minImprovement = repository.findQualityConfigNumber("minImprovement");
        Double maxRegression = repository.findQualityConfigNumber("maxRegression");
        Double editBudget = repository.findQualityConfigNumber("editBudget");
        return new SkillOptimizeGate(
                minImprovement == null ? SkillOptimizeGate.DEFAULT_MIN_IMPROVEMENT : minImprovement.doubleValue(),
                maxRegression == null ? SkillOptimizeGate.DEFAULT_MAX_REGRESSION : maxRegression.doubleValue(),
                editBudget == null ? SkillOptimizeGate.DEFAULT_EDIT_BUDGET : editBudget.intValue());
    }

    private Map<String, String> mergeEvidence(String evidenceSummaryJson,
                                              Map<String, String> external) {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        if (evidenceSummaryJson != null && !evidenceSummaryJson.trim().isEmpty()) {
            try {
                Map<?, ?> parsed = objectMapper.readValue(evidenceSummaryJson, Map.class);
                for (Map.Entry<?, ?> entry : parsed.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        merged.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            } catch (Exception ignored) {
                // corrupted summary falls back to defaults
            }
        }
        if (external != null) {
            for (Map.Entry<String, String> entry : external.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null
                        || entry.getValue().trim().isEmpty()) {
                    continue;
                }
                merged.put(entry.getKey(), entry.getValue().trim());
            }
        }
        return merged;
    }

    private List<SkillOptimizeGate.Edit> parseEdits(String editsJson) {
        if (editsJson == null || editsJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Object parsed = objectMapper.readValue(editsJson, Object.class);
            if (!(parsed instanceof List)) {
                return Collections.emptyList();
            }
            List<SkillOptimizeGate.Edit> edits = new ArrayList<SkillOptimizeGate.Edit>();
            for (Object item : (List<?>) parsed) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) item;
                SkillOptimizeGate.Edit edit = new SkillOptimizeGate.Edit();
                edit.setTarget(map.get("target") == null ? null : String.valueOf(map.get("target")));
                edit.setOp(map.get("op") == null ? null : String.valueOf(map.get("op")));
                edit.setProtectedZone(Boolean.TRUE.equals(map.get("protectedZone"))
                        || "true".equalsIgnoreCase(String.valueOf(map.get("protectedZone"))));
                edits.add(edit);
            }
            return edits;
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> pairedSummary(SkillOptimizeGate.Evaluation evidence) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        int better = 0;
        int worse = 0;
        int tie = 0;
        List<String> votes = evidence == null ? null : evidence.getPairedVotes();
        if (votes != null) {
            for (String vote : votes) {
                String normalized = SkillOptimizeGate.normalizeVote(vote);
                if (SkillOptimizeGate.VOTE_BETTER.equals(normalized)) better++;
                else if (SkillOptimizeGate.VOTE_WORSE.equals(normalized)) worse++;
                else if (SkillOptimizeGate.VOTE_TIE.equals(normalized)) tie++;
            }
        }
        summary.put("better", Integer.valueOf(better));
        summary.put("worse", Integer.valueOf(worse));
        summary.put("tie", Integer.valueOf(tie));
        summary.put("votes", votes == null ? Collections.emptyList() : votes);
        return summary;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillOptimizeCandidate decide(Long candidateId, String decision, String operator, String reason) {
        SkillOptimizeCandidate candidate = requireCandidate(candidateId);
        if (!SkillOptimizeCandidate.DECISION_PENDING.equals(candidate.getDecision())) {
            throw new SkillApiException("候选已决策，不可重复操作：" + candidate.getDecision(),
                    CODE_OPTIMIZE_CONFLICT);
        }
        SkillOptimizeTask task = requireTask(candidate.getTaskId());
        if (!SkillOptimizeTask.STATUS_PENDING_REVIEW.equals(task.getStatus())
                && !SkillOptimizeTask.STATUS_GATED_PASS.equals(task.getStatus())) {
            throw new SkillApiException("当前任务状态不允许人审决策：" + task.getStatus(),
                    CODE_OPTIMIZE_CONFLICT);
        }

        String normalized = decision == null ? "" : decision.trim().toLowerCase(Locale.ROOT);
        OffsetDateTime now = OffsetDateTime.now();
        if (DECISION_ADOPT.equals(normalized)) {
            VersionWrite version = createVersion(task, candidate);
            candidate.setDecision(SkillOptimizeCandidate.DECISION_ADOPTED);
            candidate.setResultVersionId(version.versionId);
            candidate.setResultVersionLabel(version.versionLabel);
            candidate.setDecidedBy(operator);
            candidate.setDecidedAt(now);
            repository.updateCandidate(candidate);

            task.setStatus(SkillOptimizeTask.STATUS_ADOPTED);
            repository.updateTask(task);
            return candidate;
        }
        if (DECISION_REJECT.equals(normalized)) {
            candidate.setDecision(SkillOptimizeCandidate.DECISION_REJECTED);
            candidate.setDecisionReason(reason);
            candidate.setDecidedBy(operator);
            candidate.setDecidedAt(now);
            repository.updateCandidate(candidate);

            task.setStatus(SkillOptimizeTask.STATUS_REJECTED);
            task.setFailReason(reason);
            repository.updateTask(task);
            return candidate;
        }
        throw new IllegalArgumentException("decision 仅支持 adopt / reject，收到：" + decision);
    }

    private static final class VersionWrite {
        Long versionId;
        String versionLabel;
    }

    /**
     * Adopt path: copy source version files, apply candidate unified-diff hunks,
     * write a new skill_version. Any empty result rolls back via Transactional.
     */
    private VersionWrite createVersion(SkillOptimizeTask task, SkillOptimizeCandidate candidate) {
        String diffText = candidate == null ? null : candidate.getDiffText();
        if (diffText == null || diffText.trim().isEmpty()) {
            throw adoptFailed("候选未保存改动内容（diffText 为空），拒绝生成空版本");
        }
        List<Map<String, Object>> sources = repository.listVersionFiles(task.getVersionId());
        if (sources == null || sources.isEmpty()) {
            throw adoptFailed("源版本 " + task.getVersionId() + " 没有可复制的文件内容");
        }

        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        Map<String, String> types = new LinkedHashMap<String, String>();
        for (Map<String, Object> source : sources) {
            String path = String.valueOf(source.get("path"));
            Object content = source.get("content");
            byte[] bytes = content instanceof byte[] ? (byte[]) content : new byte[0];
            files.put(path, bytes);
            Object contentType = source.get("content_type");
            types.put(path, contentType == null ? "text/plain" : String.valueOf(contentType));
        }

        int applied = applyDiff(diffText, files);
        if (applied <= 0) {
            throw adoptFailed("候选 diff 未产生任何可应用的文件改动");
        }
        boolean anyContent = false;
        for (byte[] content : files.values()) {
            if (content != null && content.length > 0) {
                anyContent = true;
                break;
            }
        }
        if (!anyContent) {
            throw adoptFailed("候选应用后的文件内容为空，拒绝生成空版本");
        }

        List<String> labels = repository.listVersionLabels(task.getSkillId());
        String maxFormal = SkillVersions.latestFormalVersion(labels);
        String nextLabel = SkillVersions.suggestNextVersion(maxFormal);
        String digest = digestOf(files);

        Long versionId = repository.insertSkillVersion(task.getSkillId(), nextLabel, digest);
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            byte[] content = entry.getValue() == null ? new byte[0] : entry.getValue();
            String path = entry.getKey();
            String contentType = types.get(path);
            if (contentType == null) {
                contentType = isMarkdown(path) ? "text/markdown" : "text/plain";
            }
            repository.insertSkillFile(versionId, path, content, contentType,
                    content.length, SkillPackageParser.sha256(content));
        }
        if (repository.countVersionFiles(versionId) != files.size()) {
            throw adoptFailed("新版本文件行数与候选不一致（" + nextLabel + "）");
        }
        VersionWrite write = new VersionWrite();
        write.versionId = versionId;
        write.versionLabel = nextLabel;
        return write;
    }

    /**
     * Apply a simplified unified diff (file sections with +/-/context lines).
     * Returns the number of files whose content actually changed.
     */
    private int applyDiff(String diffText, Map<String, byte[]> files) {
        Map<String, List<String>> pending = new LinkedHashMap<String, List<String>>();
        String currentPath = null;
        List<String> resultLines = null;
        boolean dirty = false;

        for (String rawLine : diffText.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.startsWith("diff ") || line.startsWith("index ")) {
                continue;
            }
            if (line.startsWith("--- ")) {
                flushPending(currentPath, resultLines, dirty, pending, files);
                currentPath = null;
                resultLines = null;
                dirty = false;
                continue;
            }
            if (line.startsWith("+++ ")) {
                String target = stripPathPrefix(line.substring(4).trim());
                if (target.isEmpty() || "/dev/null".equals(target)) {
                    currentPath = null;
                    resultLines = null;
                    dirty = false;
                    continue;
                }
                currentPath = target;
                byte[] existing = files.get(currentPath);
                if (existing == null) {
                    // allow create-style additions only when path is listed as new
                    existing = new byte[0];
                }
                resultLines = new ArrayList<String>(splitLines(new String(existing, StandardCharsets.UTF_8)));
                dirty = false;
                continue;
            }
            if (currentPath == null || resultLines == null) {
                continue;
            }
            if (line.startsWith("@@")) {
                continue;
            }
            if (line.startsWith("\\")) {
                continue;
            }
            if (line.startsWith("+")) {
                resultLines.add(line.substring(1));
                dirty = true;
            } else if (line.startsWith("-")) {
                String remove = line.substring(1);
                int index = indexOfLine(resultLines, remove);
                if (index >= 0) {
                    resultLines.remove(index);
                    dirty = true;
                }
            } else if (line.startsWith(" ") || line.isEmpty()) {
                // context line already present
            }
        }
        flushPending(currentPath, resultLines, dirty, pending, files);

        int changed = 0;
        for (Map.Entry<String, List<String>> entry : pending.entrySet()) {
            StringBuilder builder = new StringBuilder();
            List<String> lines = entry.getValue();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    builder.append('\n');
                }
                builder.append(lines.get(i));
            }
            if (lines.size() > 0) {
                builder.append('\n');
            }
            byte[] content = builder.toString().getBytes(StandardCharsets.UTF_8);
            if (!Arrays.equals(files.get(entry.getKey()), content)) {
                files.put(entry.getKey(), content);
                changed++;
            }
        }
        return changed;
    }

    private void flushPending(String path, List<String> lines, boolean dirty,
                              Map<String, List<String>> pending, Map<String, byte[]> files) {
        if (path == null || lines == null || !dirty) {
            return;
        }
        pending.put(path, lines);
        if (!files.containsKey(path)) {
            // new file introduced by the diff is accepted only when content non-empty
            files.put(path, new byte[0]);
        }
    }

    private static String stripPathPrefix(String target) {
        String value = target;
        int tab = value.indexOf('\t');
        if (tab >= 0) {
            value = value.substring(0, tab);
        }
        if (value.startsWith("b/")) {
            value = value.substring(2);
        } else if (value.startsWith("a/")) {
            value = value.substring(2);
        }
        return value.trim();
    }

    private static int indexOfLine(List<String> lines, String target) {
        for (int i = 0; i < lines.size(); i++) {
            if (target.equals(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<String>();
        }
        String normalized = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        List<String> lines = new ArrayList<String>();
        for (String line : normalized.split("\n", -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return lines;
    }

    private static boolean isMarkdown(String path) {
        return path != null && (path.equalsIgnoreCase("SKILL.md")
                || path.toLowerCase(Locale.ROOT).endsWith(".md"));
    }

    private static String digestOf(Map<String, byte[]> files) {
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            joined.append(entry.getKey()).append(':')
                    .append(SkillPackageParser.sha256(entry.getValue())).append('\n');
        }
        return SkillPackageParser.sha256(joined.toString().getBytes(StandardCharsets.UTF_8));
    }

    private SkillApiException adoptFailed(String message) {
        log.warn("skill optimize adopt failed: {}", message);
        return new SkillApiException(message, CODE_ADOPT_FAILED);
    }

    @Override
    public SkillOptimizeCandidate getCandidate(Long candidateId) {
        return requireCandidate(candidateId);
    }

    @Override
    public List<SkillOptimizeCandidate> listCandidates(Long taskId) {
        return repository.listCandidates(taskId);
    }

    private SkillOptimizeTask requireTask(Long taskId) {
        SkillOptimizeTask task = repository.findTask(taskId);
        if (task == null) {
            throw new SkillApiException("优化任务不存在：" + taskId, CODE_OPTIMIZE_TASK_NOT_FOUND);
        }
        return task;
    }

    private SkillOptimizeCandidate requireCandidate(Long candidateId) {
        SkillOptimizeCandidate candidate = repository.findCandidate(candidateId);
        if (candidate == null) {
            throw new SkillApiException("优化候选不存在：" + candidateId, CODE_CANDIDATE_NOT_FOUND);
        }
        return candidate;
    }

    private String normalizeMode(String mode) {
        String value = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if ("repair".equals(value) || "edit".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("mode 仅支持 repair / edit，收到：" + mode);
    }

    private String normalizeRunMode(String runMode) {
        String value = runMode == null ? "" : runMode.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "auto".equals(value)) {
            return "auto";
        }
        if ("stepwise".equals(value)) {
            return "stepwise";
        }
        throw new IllegalArgumentException("runMode 仅支持 auto / stepwise，收到：" + runMode);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append("、");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }
}
