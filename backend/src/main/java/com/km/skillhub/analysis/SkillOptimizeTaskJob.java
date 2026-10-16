package com.km.skillhub.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.km.skillhub.skill.SkillApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optimize task consumer. Create only enqueues; this job claims queued tasks,
 * writes ReAct trajectory steps, generates a candidate via AnalysisModelClient,
 * then runs the five gates.
 *
 * <p>LLM disabled/unavailable never throws out of the scheduler: the task moves
 * to an explainable gated_fail (or waiting_input for stepwise pause points).</p>
 */
@Component
public class SkillOptimizeTaskJob implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SkillOptimizeTaskJob.class);

    private static final String SYSTEM_PROMPT = "你是 Skill 优化执行器。只输出 JSON，不要输出 Markdown。"
            + "根据任务目标生成一份有界 Skill 文本改动候选。"
            + "JSON 必须包含 summary、diffText、edits、baseline、candidate、tests、votes。"
            + "baseline/candidate 包含 coverage、failureRate、qualityScore；tests 包含 total、passed；"
            + "votes 是 better/worse/tie 数组，最多 3 个；edits 每项包含 target、op、protectedZone。"
            + "diffText 必须使用 ---/+++ 文件头标明每个修改文件，edits.target 必须与文件路径一致。";

    private final SkillOptimizeRepository repository;
    private final SkillOptimizeService optimizeService;
    private final AnalysisModelClient modelClient;
    private final ObjectMapper objectMapper;

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final int workerPoolSize;
    private final ExecutorService workerPool;

    @Autowired
    public SkillOptimizeTaskJob(SkillOptimizeRepository repository,
                                SkillOptimizeService optimizeService,
                                AnalysisModelClient modelClient,
                                ObjectMapper objectMapper,
                                @Value("${skillhub.optimize.workers:2}") int workers) {
        this.repository = repository;
        this.optimizeService = optimizeService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.workerPoolSize = workers > 0 ? workers : 2;
        this.workerPool = Executors.newFixedThreadPool(this.workerPoolSize, workerThreadFactory());
    }

    /** Test-friendly constructor: workers<=0 runs synchronously on the caller thread. */
    public SkillOptimizeTaskJob(SkillOptimizeRepository repository,
                                SkillOptimizeService optimizeService,
                                AnalysisModelClient modelClient,
                                ObjectMapper objectMapper,
                                int workers,
                                boolean async) {
        this.repository = repository;
        this.optimizeService = optimizeService;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.workerPoolSize = workers > 0 ? workers : 0;
        this.workerPool = (async && this.workerPoolSize > 0)
                ? Executors.newFixedThreadPool(this.workerPoolSize, workerThreadFactory())
                : null;
    }

    private static ThreadFactory workerThreadFactory() {
        final AtomicInteger sequence = new AtomicInteger(1);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "skill-optimize-worker-" + sequence.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    @Scheduled(fixedDelayString = "${skillhub.optimize.worker-delay-ms:5000}", initialDelay = 1000L)
    public void processOnce() {
        failInterruptedReactTasks();
        if (workerBacklogFull()) {
            return;
        }
        List<SkillOptimizeTask> tasks = repository.findQueuedTasks(1);
        if (tasks.isEmpty()) {
            return;
        }
        SkillOptimizeTask task = tasks.get(0);
        log.info("skill optimize task claimed: taskId={}, skillId={}, versionId={}, mode={}",
                task.getId(), task.getSkillId(), task.getVersionId(), task.getMode());
        inFlight.add(task.getId());
        try {
            optimizeService.markRunning(task.getId());
            submitToWorker(new Runnable() {
                @Override
                public void run() {
                    executeTask(task);
                }
            });
        } catch (RuntimeException ex) {
            inFlight.remove(task.getId());
            failTaskWithLoggedError(task, ex);
        }
    }

    /** Public entry for tests and external triggers: run one task synchronously. */
    public void executeTask(SkillOptimizeTask task) {
        try {
            runPipeline(task);
            log.info("skill optimize task processed: taskId={}", task.getId());
        } catch (RuntimeException ex) {
            failTaskWithLoggedError(task, ex);
        } finally {
            inFlight.remove(task.getId());
        }
    }

    /**
     * Stepwise resume entry (T14 workbench bridge): call after
     * {@link SkillOptimizeService#advance} flipped the task back to running.
     * Continues from the pause point (files already read → LLM stage) instead
     * of re-running the pipeline, which would just re-pause. Marked inFlight
     * before submit so the interrupted-task scan cannot mis-kill it; cleared
     * by {@link #executeResume} in all cases. Validation (existence / running
     * status) happens synchronously; execution is submitted to the worker pool
     * (synchronous under the test constructor).
     */
    public void resumeTask(Long taskId) {
        SkillOptimizeTask task = repository.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("优化任务不存在：" + taskId);
        }
        if (!SkillOptimizeTask.STATUS_RUNNING.equals(task.getStatus())) {
            throw new IllegalStateException("仅 running 任务可续跑，当前：" + task.getStatus());
        }
        inFlight.add(task.getId());
        try {
            submitToWorker(new Runnable() {
                @Override
                public void run() {
                    executeResume(task);
                }
            });
        } catch (RuntimeException ex) {
            inFlight.remove(task.getId());
            throw new IllegalStateException("优化任务续跑提交失败：" + taskId, ex);
        }
    }

    /** Resume body: append a resume marker step, then continue at the LLM stage. */
    private void executeResume(SkillOptimizeTask task) {
        try {
            int stepNo = repository.listReactSteps(task.getId(), null, 2000).size();
            stepNo = appendStep(task, stepNo + 1, "分步续跑：从暂停阶段继续", "resume", null,
                    "用户已确认，继续生成候选改动");
            List<Map<String, Object>> files = repository.listVersionFiles(task.getVersionId());
            if (files.isEmpty()) {
                failTask(task, "源版本没有可读取的文件内容");
                return;
            }
            runLlmStage(task, files, stepNo);
            log.info("skill optimize task resume completed: taskId={}", task.getId());
        } catch (RuntimeException ex) {
            failTaskWithLoggedError(task, ex);
        } finally {
            inFlight.remove(task.getId());
        }
    }

    private boolean workerBacklogFull() {
        if (!(workerPool instanceof ThreadPoolExecutor)) {
            return false;
        }
        return ((ThreadPoolExecutor) workerPool).getQueue().size() >= workerPoolSize * 10;
    }

    private void submitToWorker(Runnable work) {
        if (workerPool == null) {
            work.run();
            return;
        }
        workerPool.execute(work);
    }

    private void failInterruptedReactTasks() {
        List<SkillOptimizeTask> running = repository.findRunningReactTasks(20);
        for (SkillOptimizeTask task : running) {
            if (task == null || task.getId() == null || inFlight.contains(task.getId())) {
                continue;
            }
            boolean hasEvidence = (task.getReactStateJson() != null
                    && !task.getReactStateJson().trim().isEmpty())
                    || repository.hasReactSteps(task.getId());
            if (hasEvidence) {
                log.warn("skill react task interrupted detected: taskId={}", task.getId());
                failTaskWithLoggedError(task, new IllegalStateException("AI 执行中断，请重新发起"));
            }
        }
    }

    private void runPipeline(SkillOptimizeTask task) {
        int stepNo = 0;
        stepNo = appendStep(task, ++stepNo, "读取当前版本文件", "read_skill", null,
                "正在读取当前版本文件");
        optimizeService.updateProgress(task.getId(), SkillOptimizeTask.STAGE_READING_SKILL,
                10, "正在读取当前版本文件");

        List<Map<String, Object>> files = repository.listVersionFiles(task.getVersionId());
        if (files.isEmpty()) {
            failTask(task, "源版本没有可读取的文件内容");
            return;
        }
        // stepwise: pause after reading so the user can confirm
        if ("stepwise".equals(task.getRunMode())) {
            task.setStatus(SkillOptimizeTask.STATUS_WAITING_INPUT);
            task.setProgressStage(SkillOptimizeTask.STAGE_READING_SKILL);
            task.setProgressPercent(Integer.valueOf(20));
            task.setProgressMessage("已读取文件，等待确认继续生成候选");
            task.setFailReason(null);
            repository.updateTask(task);
            appendStep(task, ++stepNo, "分步暂停：等待用户确认", "pause", null,
                    "{\"status\":\"waiting_input\"}");
            return;
        }
        runLlmStage(task, files, stepNo);
    }

    private void runLlmStage(SkillOptimizeTask task, List<Map<String, Object>> files, int stepNo) {
        String input = buildTaskInput(task, files);
        optimizeService.updateProgress(task.getId(), SkillOptimizeTask.STAGE_GENERATING_CANDIDATE,
                30, "正在生成候选改动");
        stepNo = appendStep(task, ++stepNo, "请求 AI 生成候选", "llm_requesting",
                "{\"inputChars\":" + input.length() + "}", "已发起 LLM 候选生成请求");

        if (modelClient == null || !modelClient.isEnabled()) {
            failTask(task, "AI 模型未启用或未配置 endpoint，无法生成候选；请在系统配置中启用分析模型后重试");
            return;
        }
        long startedAt = System.currentTimeMillis();
        Optional<String> raw = modelClient.chat("optimize-task-" + task.getId(), SYSTEM_PROMPT, input);
        long durationMs = System.currentTimeMillis() - startedAt;
        if (!raw.isPresent()) {
            appendStep(task, ++stepNo, "AI 未返回可用内容", "llm_responded",
                    "{\"durationMs\":" + durationMs + ",\"ok\":false}",
                    "AI 不可用或响应为空");
            failTask(task, "AI 调用失败或返回空内容（耗时 " + durationMs + "ms），任务已结束，可稍后重试");
            return;
        }
        appendStep(task, ++stepNo, "AI 已返回候选", "llm_responded",
                "{\"durationMs\":" + durationMs + ",\"ok\":true," + "\"chars\":" + raw.get().length() + "}",
                "候选响应已收到");

        Map<String, Object> candidate;
        try {
            candidate = parseCandidateJson(raw.get());
        } catch (IllegalArgumentException invalid) {
            appendStep(task, ++stepNo, "候选 JSON 解析失败", "parse_failed", null, invalid.getMessage());
            failTask(task, "AI 返回内容不是合法 JSON 候选");
            return;
        }

        String summary = text(candidate.get("summary"));
        String diffText = text(candidate.get("diffText"));
        if (summary.isEmpty() || diffText.trim().isEmpty()) {
            failTask(task, "AI 候选缺少 summary 或 diffText");
            return;
        }
        List<SkillOptimizeGate.Edit> edits = parseEditsLoose(candidate.get("edits"));
        SkillOptimizeCandidate saved;
        try {
            saved = optimizeService.addCandidate(task.getId(), summary, diffText, edits);
        } catch (SkillApiException | IllegalStateException conflict) {
            // cancelled while running
            log.warn("skill optimize candidate skipped: taskId={}, reason={}",
                    task.getId(), conflict.getMessage());
            return;
        }
        stepNo = appendStep(task, ++stepNo, "候选已落库", "save_candidate",
                "{\"candidateId\":" + saved.getId() + "}", "候选已保存");

        optimizeService.updateProgress(task.getId(), SkillOptimizeTask.STAGE_RUNNING_GATES,
                75, "正在执行五项检查");
        appendStep(task, ++stepNo, "执行五道门", "gate_checking", null, "开始执行五项门禁检查");

        SkillOptimizeGate.Metrics baseline = toMetrics(candidate.get("baseline"));
        SkillOptimizeGate.Metrics candidateMetrics = toMetrics(candidate.get("candidate"));
        SkillOptimizeGate.Evaluation evaluation = toEvaluation(candidate);
        try {
            optimizeService.evaluateCandidate(saved.getId(), baseline, candidateMetrics, evaluation);
        } catch (RuntimeException ex) {
            failTaskWithLoggedError(task, ex);
        }
    }

    private int appendStep(SkillOptimizeTask task, int stepNo, String thought, String actionType,
                           String actionJson, String observationMessage) {
        try {
            SkillReactStep step = new SkillReactStep();
            step.setTaskId(task.getId());
            step.setStepNo(Integer.valueOf(stepNo));
            step.setThought(thought);
            step.setActionType(actionType);
            step.setActionJson(actionJson);
            Map<String, Object> observation = new LinkedHashMap<String, Object>();
            observation.put("ok", Boolean.TRUE);
            observation.put("message", observationMessage);
            step.setObservationJson(writeJsonQuietly(observation));
            step.setDurationMs(Long.valueOf(0L));
            repository.insertReactStep(step);
            // light react state so interrupt scan can see evidence
            Map<String, Object> state = new LinkedHashMap<String, Object>();
            state.put("cursorStep", Integer.valueOf(stepNo));
            state.put("hash", "");
            task.setReactStateJson(writeJsonQuietly(state));
            repository.updateTask(task);
        } catch (RuntimeException ex) {
            log.warn("skill react step write failed: taskId={}, stepNo={}, errorType={}",
                    task.getId(), Integer.valueOf(stepNo), ex.getClass().getSimpleName());
        }
        return stepNo;
    }

    private String writeJsonQuietly(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String buildTaskInput(SkillOptimizeTask task, List<Map<String, Object>> files) {
        StringBuilder input = new StringBuilder();
        input.append("mode=").append(task.getMode()).append('\n');
        input.append("目标优先项：").append(text(task.getPriorityListJson())).append('\n');
        if (task.getTargetIssueIdsJson() != null && !task.getTargetIssueIdsJson().trim().isEmpty()
                && !"[]".equals(task.getTargetIssueIdsJson().trim())) {
            input.append("目标问题 ids：").append(task.getTargetIssueIdsJson()).append('\n');
        }
        input.append("当前版本文件：\n");
        int previewFiles = Math.min(files.size(), 8);
        for (int i = 0; i < previewFiles; i++) {
            Map<String, Object> file = files.get(i);
            String path = text(file.get("path"));
            byte[] content = file.get("content") instanceof byte[]
                    ? (byte[]) file.get("content") : new byte[0];
            String textContent = new String(content, StandardCharsets.UTF_8);
            if (textContent.length() > 4000) {
                textContent = textContent.substring(0, 4000) + "…";
            }
            input.append("### ").append(path).append('\n').append(textContent).append('\n');
        }
        if (files.size() > previewFiles) {
            input.append("（其余 ").append(files.size() - previewFiles).append(" 个文件已省略预览）\n");
        }
        return AnalysisModelClient.redact(input.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCandidateJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            Object parsed = objectMapper.readValue(text, Object.class);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (Exception ignored) {
            // fall through
        }
        throw new IllegalArgumentException("AI 返回内容不是合法 JSON 候选");
    }

    private List<SkillOptimizeGate.Edit> parseEditsLoose(Object raw) {
        if (!(raw instanceof List)) {
            return Collections.emptyList();
        }
        List<SkillOptimizeGate.Edit> edits = new ArrayList<SkillOptimizeGate.Edit>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) item;
            SkillOptimizeGate.Edit edit = new SkillOptimizeGate.Edit();
            Object target = map.get("target") != null ? map.get("target") : map.get("path");
            edit.setTarget(target == null ? null : String.valueOf(target));
            edit.setOp(map.get("op") == null ? "replace" : String.valueOf(map.get("op")));
            edit.setProtectedZone(Boolean.TRUE.equals(map.get("protectedZone"))
                    || "true".equalsIgnoreCase(String.valueOf(map.get("protectedZone"))));
            edits.add(edit);
        }
        return edits;
    }

    private SkillOptimizeGate.Metrics toMetrics(Object raw) {
        if (!(raw instanceof Map)) {
            return new SkillOptimizeGate.Metrics();
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        return new SkillOptimizeGate.Metrics(
                toDouble(map.get("coverage")),
                toDouble(map.get("failureRate")),
                toDouble(map.get("qualityScore")));
    }

    private SkillOptimizeGate.Evaluation toEvaluation(Map<String, Object> candidate) {
        SkillOptimizeGate.Evaluation evaluation = new SkillOptimizeGate.Evaluation();
        Object tests = candidate.get("tests");
        if (tests instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) tests;
            int total = toInt(map.get("total"));
            int passed = toInt(map.get("passed"));
            evaluation.setHasTests(total > 0);
            evaluation.setTestTotal(total);
            evaluation.setTestPassed(passed);
            evaluation.setAllPassed(total > 0 && passed >= total);
        } else {
            evaluation.setRequireTests(true);
            evaluation.setHasTests(false);
            evaluation.setAllPassed(false);
        }
        List<String> votes = new ArrayList<String>();
        Object rawVotes = candidate.get("votes");
        if (rawVotes instanceof List) {
            for (Object vote : (List<?>) rawVotes) {
                if (vote != null) {
                    votes.add(String.valueOf(vote));
                }
            }
        }
        evaluation.setPairedVotes(votes);
        return evaluation;
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return Double.valueOf(((Number) value).doubleValue());
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int toInt(Object value) {
        Double number = toDouble(value);
        return number == null ? 0 : number.intValue();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void failTask(SkillOptimizeTask task, String reason) {
        try {
            optimizeService.failTask(task.getId(), reason);
        } catch (RuntimeException ex) {
            log.warn("skill optimize failTask failed: taskId={}, errorType={}",
                    task.getId(), ex.getClass().getSimpleName());
        }
    }

    private void failTaskWithLoggedError(SkillOptimizeTask task, RuntimeException ex) {
        String detail = ex.getMessage() == null ? "" : ex.getMessage().trim().replace('\n', ' ');
        if (detail.length() > 200) {
            detail = detail.substring(0, 200) + "…";
        }
        log.warn("skill optimize task failed: taskId={}, errorType={}, message={}",
                task.getId(), ex.getClass().getSimpleName(), detail);
        failTask(task, "优化执行失败：" + ex.getClass().getSimpleName()
                + (detail.isEmpty() ? "" : "（" + detail + "）"));
    }

    @Override
    public void destroy() {
        if (workerPool == null) {
            return;
        }
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException ex) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
