package com.km.skillhub.review.mapper;

import com.km.skillhub.review.model.entity.SkillReviewTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ReviewTaskMapper {
    @Select("SELECT rt.id, rt.version_digest, sv.asset_id, sa.owner_scope_id AS scope_id, sa.name AS asset_name, "
            + "sv.version_label, rt.applicant_id, rt.reviewer_id, rt.status, rt.review_comment, rt.submitted_at, rt.reviewed_at "
            + "FROM skill_review_task rt JOIN skill_version sv ON sv.version_digest = rt.version_digest "
            + "JOIN skill_asset sa ON sa.id = sv.asset_id WHERE rt.version_digest = #{versionDigest} "
            + "AND rt.status = 'PENDING'")
    SkillReviewTaskEntity findPending(@Param("versionDigest") String versionDigest);

    @Select("SELECT rt.id, rt.version_digest, sv.asset_id, sa.owner_scope_id AS scope_id, sa.name AS asset_name, "
            + "sv.version_label, rt.applicant_id, rt.reviewer_id, rt.status, rt.review_comment, rt.submitted_at, rt.reviewed_at "
            + "FROM skill_review_task rt JOIN skill_version sv ON sv.version_digest = rt.version_digest "
            + "JOIN skill_asset sa ON sa.id = sv.asset_id "
            + "WHERE EXISTS (SELECT 1 FROM principal_scope_role psr "
            + "JOIN principal_account pa ON pa.id = psr.principal_id "
            + "WHERE pa.username = #{username} AND pa.enabled = TRUE "
            + "AND psr.scope_id = sa.owner_scope_id "
            + "AND psr.role_key IN ('REVIEWER', 'GOVERNANCE_ADMIN')) "
            + "AND (#{status} IS NULL OR rt.status = #{status}) ORDER BY rt.submitted_at DESC, rt.id DESC")
    List<SkillReviewTaskEntity> findAuthorized(@Param("username") String username, @Param("status") String status);

    @Insert("INSERT INTO skill_review_task(version_digest, applicant_id, status, review_comment) "
            + "VALUES(#{versionDigest}, #{applicantId}, 'PENDING', #{reviewComment})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(SkillReviewTaskEntity entity);

    @Update("UPDATE skill_review_task SET status = #{status}, reviewer_id = #{reviewerId}, "
            + "review_comment = #{comment}, reviewed_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'PENDING'")
    int finish(@Param("id") Long id, @Param("status") String status, @Param("reviewerId") String reviewerId,
               @Param("comment") String comment);

    @Select("SELECT rt.id, rt.version_digest, sv.asset_id, sa.owner_scope_id AS scope_id, sa.name AS asset_name, "
            + "sv.version_label, rt.applicant_id, rt.reviewer_id, rt.status, rt.review_comment, rt.submitted_at, rt.reviewed_at "
            + "FROM skill_review_task rt JOIN skill_version sv ON sv.version_digest = rt.version_digest "
            + "JOIN skill_asset sa ON sa.id = sv.asset_id WHERE rt.id = #{id}")
    SkillReviewTaskEntity findById(@Param("id") Long id);
}
