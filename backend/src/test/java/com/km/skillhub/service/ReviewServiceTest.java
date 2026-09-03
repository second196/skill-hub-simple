package com.km.skillhub.service;

import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.review.mapper.ReviewTaskMapper;
import com.km.skillhub.review.model.entity.SkillReviewTaskEntity;
import com.km.skillhub.review.service.ReviewService;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewServiceTest {
    @Test
    void preventsApplicantFromApprovingOwnReview() {
        ReviewTaskMapper reviewMapper = mock(ReviewTaskMapper.class);
        SkillReviewTaskEntity task = new SkillReviewTaskEntity();
        task.setId(7L); task.setApplicantId("user"); task.setScopeId(1L); task.setStatus("PENDING");
        when(reviewMapper.findById(7L)).thenReturn(task);

        ReviewService service = new ReviewService(reviewMapper, mock(SkillVersionMapper.class),
                mock(AuthorizationService.class), mock(AuditQueryService.class));

        assertThrows(IllegalArgumentException.class, () -> service.finish(7L, "APPROVED", "同意", "user"));
    }

    @Test
    void onlyCandidateVersionCanBeSubmitted() {
        SkillVersionMapper versionMapper = mock(SkillVersionMapper.class);
        SkillVersionEntity version = new SkillVersionEntity();
        version.setLifecycleState("PUBLISHED");
        when(versionMapper.findByDigest("digest")).thenReturn(version);
        ReviewService service = new ReviewService(mock(ReviewTaskMapper.class), versionMapper,
                mock(AuthorizationService.class), mock(AuditQueryService.class));

        com.km.skillhub.review.model.ReviewCommand command = new com.km.skillhub.review.model.ReviewCommand();
        command.setVersionDigest("digest");
        assertThrows(IllegalArgumentException.class, () -> service.submit(command, "user"));
    }
}
