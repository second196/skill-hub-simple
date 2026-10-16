package com.km.skillhub.analysis;

import java.util.List;
import java.util.Map;

/**
 * Skill optimize task orchestration: status machine, gates, adopt/reject.
 *
 * <p>Status machine: queued → running → gated_pass/pending_review → adopted;
 * running → waiting_input (stepwise) | gated_fail → rejected / queued;
 * pending_review → rejected (human review).</p>
 */
public interface SkillOptimizeService {

    /** Create optimize task (mode=repair | edit). */
    SkillOptimizeTask createTask(Long skillId, Long versionId, String mode,
                                 List<String> priorities, String createdBy,
                                 String runMode, List<String> targetIssueIds, Long conversationId);

    /**
     * Cancel optimize task: only queued / waiting_input / running may cancel.
     * Reuses rejected status (no new migration); failReason records user cancel.
     */
    SkillOptimizeTask cancelTask(Long taskId, String operator, String reason);

    /** Stepwise advance: waiting_input → running; note merged into inputsJson. */
    SkillOptimizeTask advance(Long taskId, String note);

    /**
     * Latest waiting_input task bound to a conversation (workbench advance entry).
     * Null when the conversation has no such task.
     */
    SkillOptimizeTask findWaitingInputTask(Long conversationId);

    /** queued → running. */
    SkillOptimizeTask markRunning(Long taskId);

    /** End task with an explainable reason when the executor cannot produce a candidate. */
    SkillOptimizeTask failTask(Long taskId, String reason);

    SkillOptimizeTask updateProgress(Long taskId, String stage, int percent, String message);

    SkillOptimizeTask getTask(Long taskId);

    List<SkillOptimizeTask> listTasks(Long skillId, String mode);

    /** Append a candidate (summary / diff / bounded edits). */
    SkillOptimizeCandidate addCandidate(Long taskId, String summary, String diffText,
                                        List<SkillOptimizeGate.Edit> edits);

    /**
     * Run five gates and advance status: all pass → pending_review; else gated_fail.
     * Gate conclusions are written onto the candidate.
     */
    SkillOptimizeCandidate evaluateCandidate(Long candidateId,
                                             SkillOptimizeGate.Metrics baseline,
                                             SkillOptimizeGate.Metrics candidate,
                                             SkillOptimizeGate.Evaluation evidence);

    /** Same as evaluate with per-gate evidence type overrides. */
    SkillOptimizeCandidate evaluateCandidate(Long candidateId,
                                             SkillOptimizeGate.Metrics baseline,
                                             SkillOptimizeGate.Metrics candidate,
                                             SkillOptimizeGate.Evaluation evidence,
                                             Map<String, String> evidenceTypes);

    /**
     * Human decision: adopt creates a new skill version; reject records reason.
     *
     * @param decision adopt | reject
     */
    SkillOptimizeCandidate decide(Long candidateId, String decision, String operator, String reason);

    SkillOptimizeCandidate getCandidate(Long candidateId);

    List<SkillOptimizeCandidate> listCandidates(Long taskId);
}
