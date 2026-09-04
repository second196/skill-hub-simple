package com.km.skillhub.service;

import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.gate.mapper.GateEvidenceMapper;
import com.km.skillhub.gate.model.entity.GateEvidenceEntity;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.mapper.version.SkillVersionMapper;
import com.km.skillhub.review.model.ReviewCommand;
import com.km.skillhub.review.mapper.ReviewTaskMapper;
import com.km.skillhub.review.model.entity.SkillReviewTaskEntity;
import com.km.skillhub.review.service.ReviewService;
import com.km.skillhub.version.model.VersionTransitionCommand;
import com.km.skillhub.version.model.entity.SkillVersionEntity;
import com.km.skillhub.version.service.LifecycleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTest {
    @Test
    void preventsApplicantFromApprovingOwnReview() {
        ReviewTaskMapper reviewMapper = mock(ReviewTaskMapper.class);
        SkillReviewTaskEntity task = new SkillReviewTaskEntity();
        task.setId(7L); task.setApplicantId("user"); task.setScopeId(1L); task.setStatus("PENDING");
        when(reviewMapper.findById(7L)).thenReturn(task);

        ReviewService service = new ReviewService(reviewMapper, mock(SkillVersionMapper.class),
                mock(AuthorizationService.class), mock(AuditQueryService.class),
                mock(GateEvidenceMapper.class), mock(LifecycleService.class));

        assertThrows(IllegalArgumentException.class, () -> service.finish(7L, "APPROVED", "同意", "user"));
    }

    @Test
    void onlyCandidateVersionCanBeSubmitted() {
        SkillVersionMapper versionMapper = mock(SkillVersionMapper.class);
        SkillVersionEntity version = new SkillVersionEntity();
        version.setLifecycleState("PUBLISHED");
        when(versionMapper.findByDigest("digest")).thenReturn(version);
        ReviewService service = new ReviewService(mock(ReviewTaskMapper.class), versionMapper,
                mock(AuthorizationService.class), mock(AuditQueryService.class),
                mock(GateEvidenceMapper.class), mock(LifecycleService.class));

        ReviewCommand command = new ReviewCommand();
        command.setVersionDigest("digest");
        assertThrows(IllegalArgumentException.class, () -> service.submit(command, "user"));
    }

    @Test
    void keepsDraftWhenRequiredEvidenceIsMissing() {
        SkillVersionMapper versionMapper = mock(SkillVersionMapper.class);
        SkillVersionEntity version = version("DRAFT");
        when(versionMapper.findByDigest("digest")).thenReturn(version);
        when(versionMapper.findOwnerScopeId("digest")).thenReturn(1L);
        LifecycleService lifecycleService = mock(LifecycleService.class);
        ReviewService service = new ReviewService(mock(ReviewTaskMapper.class), versionMapper,
                mock(AuthorizationService.class), mock(AuditQueryService.class),
                mock(GateEvidenceMapper.class), lifecycleService);
        ReviewCommand command = new ReviewCommand();
        command.setVersionDigest("digest");

        assertThrows(IllegalArgumentException.class, () -> service.submit(command, "user"));

        verify(lifecycleService, never()).transition(any(VersionTransitionCommand.class));
    }

    @Test
    void transitionsDraftToCandidateWhenReviewEvidenceIsComplete() {
        ReviewTaskMapper reviewMapper = mock(ReviewTaskMapper.class);
        SkillVersionMapper versionMapper = mock(SkillVersionMapper.class);
        GateEvidenceMapper evidenceMapper = mock(GateEvidenceMapper.class);
        LifecycleService lifecycleService = mock(LifecycleService.class);
        SkillVersionEntity version = version("DRAFT");
        when(versionMapper.findByDigest("digest")).thenReturn(version);
        when(versionMapper.findOwnerScopeId("digest")).thenReturn(1L);
        when(evidenceMapper.findLatestForReview("digest")).thenReturn(Arrays.asList(
                evidence("STATIC_SCAN"), evidence("EVALUATION"), evidence("RISK")));
        SkillReviewTaskEntity pending = new SkillReviewTaskEntity();
        pending.setId(8L);
        pending.setStatus("PENDING");
        when(reviewMapper.findPending("digest")).thenReturn(null, pending);
        ReviewService service = new ReviewService(reviewMapper, versionMapper,
                mock(AuthorizationService.class), mock(AuditQueryService.class),
                evidenceMapper, lifecycleService);
        ReviewCommand command = new ReviewCommand();
        command.setVersionDigest("digest");

        SkillReviewTaskEntity result = service.submit(command, "user");

        assertEquals(8L, result.getId());
        ArgumentCaptor<VersionTransitionCommand> transition =
                ArgumentCaptor.forClass(VersionTransitionCommand.class);
        verify(lifecycleService).transition(transition.capture());
        assertEquals("CANDIDATE", transition.getValue().getTargetState());
        verify(reviewMapper).insert(any(SkillReviewTaskEntity.class));
    }

    private SkillVersionEntity version(String state) {
        SkillVersionEntity version = new SkillVersionEntity();
        version.setVersionDigest("digest");
        version.setMetadataStatus("COMPLETE");
        version.setLifecycleState(state);
        return version;
    }

    private GateEvidenceEntity evidence(String type) {
        GateEvidenceEntity evidence = new GateEvidenceEntity();
        evidence.setEvidenceType(type);
        evidence.setResult("PASS");
        evidence.setExpiresAt(OffsetDateTime.now().plusHours(1));
        return evidence;
    }
}
