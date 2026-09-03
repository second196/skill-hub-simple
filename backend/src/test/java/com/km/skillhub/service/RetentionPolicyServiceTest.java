package com.km.skillhub.service;

import com.km.skillhub.policy.mapper.RetentionPolicyMapper;
import com.km.skillhub.policy.model.entity.RetentionPolicyVersionEntity;
import com.km.skillhub.policy.service.RetentionPolicyService;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetentionPolicyServiceTest {
    @Test
    void exposesConfigurableIndustryDefaultRulesWhenNoRowsExist() {
        RetentionPolicyMapper mapper = mock(RetentionPolicyMapper.class);
        when(mapper.findAll()).thenReturn(Collections.<RetentionPolicyVersionEntity>emptyList());
        assertEquals(7, new RetentionPolicyService(mapper).list().size());
    }

    @Test
    void rejectsPolicyApprovedByTheApplicant() {
        RetentionPolicyMapper mapper = mock(RetentionPolicyMapper.class);
        RetentionPolicyVersionEntity rule = new RetentionPolicyVersionEntity();
        rule.setScopeType("COMPANY"); rule.setScopeId(1L); rule.setDataClass("RAW_RUNTIME_EVENT");
        rule.setRetentionType("DURATION"); rule.setRetentionSeconds(86400L); rule.setPolicyVersion("v2");
        rule.setEffectiveAt(java.time.OffsetDateTime.now()); rule.setApprovedBy("alice");
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicyService(mapper).create(rule, "alice"));
    }
}
