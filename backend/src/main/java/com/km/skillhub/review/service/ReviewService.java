package com.km.skillhub.review.service;

import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.gate.mapper.GateEvidenceMapper;
import com.km.skillhub.gate.model.entity.GateEvidenceEntity;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.review.mapper.ReviewTaskMapper;
import com.km.skillhub.review.model.ReviewCommand;
import com.km.skillhub.review.model.entity.SkillReviewTaskEntity;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import com.km.skillhub.version.model.VersionTransitionCommand;
import com.km.skillhub.version.service.LifecycleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ReviewService {
    private final ReviewTaskMapper reviewMapper;
    private final SkillVersionMapper versionMapper;
    private final AuthorizationService authorizationService;
    private final AuditQueryService auditService;
    private final GateEvidenceMapper evidenceMapper;
    private final LifecycleService lifecycleService;
    private static final Set<String> REQUIRED_REVIEW_EVIDENCE = new HashSet<String>(
            Arrays.asList("STATIC_SCAN", "EVALUATION", "RISK"));

    public ReviewService(ReviewTaskMapper reviewMapper, SkillVersionMapper versionMapper,
                         AuthorizationService authorizationService, AuditQueryService auditService,
                         GateEvidenceMapper evidenceMapper, LifecycleService lifecycleService) {
        this.reviewMapper = reviewMapper;
        this.versionMapper = versionMapper;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.evidenceMapper = evidenceMapper;
        this.lifecycleService = lifecycleService;
    }

    @Transactional
    public SkillReviewTaskEntity submit(ReviewCommand command, String applicant) {
        if (command == null || blank(command.getVersionDigest()) || blank(applicant)) {
            throw new IllegalArgumentException("审核申请信息不完整");
        }
        SkillVersionEntity version = versionMapper.findByDigest(command.getVersionDigest());
        if (version == null || !("DRAFT".equals(version.getLifecycleState())
                || "CANDIDATE".equals(version.getLifecycleState()))) {
            throw new IllegalArgumentException("只有草稿或候选版本可以提交审核");
        }
        if (!"COMPLETE".equals(version.getMetadataStatus())) {
            throw new IllegalArgumentException("Skill 元数据不完整，不能提交审核");
        }
        Long scopeId = versionMapper.findOwnerScopeId(command.getVersionDigest());
        if (scopeId == null) throw new IllegalArgumentException("Skill 归属范围不存在");
        authorizationService.requireRole(applicant, scopeId, "ASSET_CONTRIBUTOR");
        if (reviewMapper.findPending(command.getVersionDigest()) != null) {
            throw new IllegalArgumentException("该版本已有待处理审核");
        }
        requireReviewEvidence(command.getVersionDigest());
        String sourceState = version.getLifecycleState();
        if ("DRAFT".equals(sourceState)) {
            VersionTransitionCommand transition = new VersionTransitionCommand();
            transition.setVersionDigest(command.getVersionDigest());
            transition.setTargetState("CANDIDATE");
            transition.setReason("提交发布审核");
            lifecycleService.transition(transition);
        }
        SkillReviewTaskEntity task = new SkillReviewTaskEntity();
        task.setVersionDigest(command.getVersionDigest());
        task.setApplicantId(applicant);
        task.setReviewComment(command.getComment());
        reviewMapper.insert(task);
        auditService.record(applicant, "SUBMIT_REVIEW", "SKILL_VERSION", command.getVersionDigest(),
                command.getComment(), "{\"state\":\"" + sourceState + "\"}",
                "{\"state\":\"CANDIDATE\",\"review\":\"PENDING\"}",
                "ASSET", scopeId, null);
        SkillReviewTaskEntity result = reviewMapper.findPending(command.getVersionDigest());
        if (result == null) throw new IllegalStateException("审核任务创建后不可见");
        return result;
    }

    public List<SkillReviewTaskEntity> list(String status, String reviewer) {
        if (status != null && !status.matches("PENDING|APPROVED|REJECTED|WITHDRAWN")) {
            throw new IllegalArgumentException("审核状态不合法");
        }
        return reviewMapper.findAuthorized(reviewer, status);
    }

    @Transactional
    public SkillReviewTaskEntity finish(Long id, String targetStatus, String comment, String reviewer) {
        if (id == null || blank(reviewer) || targetStatus == null
                || !targetStatus.matches("APPROVED|REJECTED|WITHDRAWN")) {
            throw new IllegalArgumentException("审核操作信息不完整");
        }
        SkillReviewTaskEntity task = reviewMapper.findById(id);
        if (task == null || !"PENDING".equals(task.getStatus())) {
            throw new IllegalArgumentException("审核任务不存在或已处理");
        }
        if (reviewer.equals(task.getApplicantId())) {
            throw new IllegalArgumentException("申请人不能审批自己的审核申请");
        }
        authorizationService.requireRole(reviewer, task.getScopeId(), "REVIEWER");
        int updated = reviewMapper.finish(id, targetStatus, reviewer, comment);
        if (updated != 1) throw new IllegalArgumentException("审核任务已被其他人处理");
        auditService.record(reviewer, targetStatus.equals("APPROVED") ? "APPROVE_REVIEW" : "REJECT_REVIEW",
                "SKILL_REVIEW_TASK", String.valueOf(id), comment, "{\"status\":\"PENDING\"}",
                "{\"status\":\"" + targetStatus + "\"}", "ASSET", task.getScopeId(), null);
        return reviewMapper.findById(id);
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private void requireReviewEvidence(String versionDigest) {
        List<GateEvidenceEntity> evidence = evidenceMapper.findLatestForReview(versionDigest);
        Set<String> passed = new HashSet<String>();
        OffsetDateTime now = OffsetDateTime.now();
        if (evidence != null) {
            for (GateEvidenceEntity item : evidence) {
                if (item != null && REQUIRED_REVIEW_EVIDENCE.contains(item.getEvidenceType())
                        && "PASS".equals(item.getResult())
                        && (item.getExpiresAt() == null || item.getExpiresAt().isAfter(now))) {
                    passed.add(item.getEvidenceType());
                }
            }
        }
        if (!passed.containsAll(REQUIRED_REVIEW_EVIDENCE)) {
            throw new IllegalArgumentException("静态扫描、评测或风险证据缺失、失败或已过期");
        }
    }
}
