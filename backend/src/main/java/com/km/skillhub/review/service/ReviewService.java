package com.km.skillhub.review.service;

import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.review.mapper.ReviewTaskMapper;
import com.km.skillhub.review.model.ReviewCommand;
import com.km.skillhub.review.model.entity.SkillReviewTaskEntity;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ReviewService {
    private final ReviewTaskMapper reviewMapper;
    private final SkillVersionMapper versionMapper;
    private final AuthorizationService authorizationService;
    private final AuditQueryService auditService;

    public ReviewService(ReviewTaskMapper reviewMapper, SkillVersionMapper versionMapper,
                         AuthorizationService authorizationService, AuditQueryService auditService) {
        this.reviewMapper = reviewMapper;
        this.versionMapper = versionMapper;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional
    public SkillReviewTaskEntity submit(ReviewCommand command, String applicant) {
        if (command == null || blank(command.getVersionDigest()) || blank(applicant)) {
            throw new IllegalArgumentException("审核申请信息不完整");
        }
        SkillVersionEntity version = versionMapper.findByDigest(command.getVersionDigest());
        if (version == null || !"CANDIDATE".equals(version.getLifecycleState())) {
            throw new IllegalArgumentException("只有候选版本可以提交审核");
        }
        Long scopeId = versionMapper.findOwnerScopeId(command.getVersionDigest());
        authorizationService.requireRole(applicant, scopeId, "ASSET_CONTRIBUTOR");
        if (reviewMapper.findPending(command.getVersionDigest()) != null) {
            throw new IllegalArgumentException("该版本已有待处理审核");
        }
        SkillReviewTaskEntity task = new SkillReviewTaskEntity();
        task.setVersionDigest(command.getVersionDigest());
        task.setApplicantId(applicant);
        task.setReviewComment(command.getComment());
        reviewMapper.insert(task);
        auditService.record(applicant, "SUBMIT_REVIEW", "SKILL_VERSION", command.getVersionDigest(),
                command.getComment(), "{\"state\":\"CANDIDATE\"}", "{\"review\":\"PENDING\"}",
                "ASSET", scopeId, null);
        return reviewMapper.findPending(command.getVersionDigest());
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
}
