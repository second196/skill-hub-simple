package com.km.skillhub.governance.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface ApprovalMapper {
    @Insert("INSERT INTO approval_record(decision_id, applicant_id, approver_id, approval_state, comment, "
            + "actor_type, approved_at) VALUES(#{decisionId}, #{applicantId}, #{approverId}, 'APPROVED', "
            + "#{comment}, 'USER', CURRENT_TIMESTAMP)")
    int insert(@Param("decisionId") Long decisionId, @Param("applicantId") String applicantId,
               @Param("approverId") String approverId, @Param("comment") String comment);
}
