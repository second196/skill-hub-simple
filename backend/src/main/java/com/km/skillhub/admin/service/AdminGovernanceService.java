package com.km.skillhub.admin.service;

import com.km.skillhub.admin.model.AccountAdminVO;
import com.km.skillhub.admin.model.MemberCommand;
import com.km.skillhub.audit.service.AuditQueryService;
import com.km.skillhub.governance.mapper.AuthorizationMapper;
import com.km.skillhub.governance.model.entity.AccountEntity;
import com.km.skillhub.governance.service.AuthorizationService;
import com.km.skillhub.mapper.governance.AccountMapper;
import com.km.skillhub.namespace.mapper.SkillNamespaceMapper;
import com.km.skillhub.namespace.model.entity.SkillNamespaceEntity;
import com.km.skillhub.namespace.model.vo.NamespaceMemberVO;
import com.km.skillhub.version.mapper.SkillVersionTagMapper;
import com.km.skillhub.version.model.entity.SkillVersionTagEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class AdminGovernanceService {
    private final AccountMapper accountMapper;
    private final SkillNamespaceMapper namespaceMapper;
    private final SkillVersionTagMapper tagMapper;
    private final AuthorizationService authorizationService;
    private final AuditQueryService auditService;

    public AdminGovernanceService(AccountMapper accountMapper, SkillNamespaceMapper namespaceMapper,
                                  SkillVersionTagMapper tagMapper, AuthorizationService authorizationService,
                                  AuditQueryService auditService) {
        this.accountMapper = accountMapper; this.namespaceMapper = namespaceMapper;
        this.tagMapper = tagMapper; this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    public List<AccountAdminVO> accounts(String actor) {
        requireAdmin(actor);
        List<AccountAdminVO> result = new ArrayList<AccountAdminVO>();
        for (AccountEntity account : accountMapper.findAllAccounts()) {
            result.add(new AccountAdminVO(account.getId(), account.getUsername(), account.isEnabled()));
        }
        return result;
    }

    @Transactional
    public void setAccountEnabled(String username, boolean enabled, String actor) {
        requireAdmin(actor);
        if (username == null || username.trim().isEmpty() || actor.equals(username) && !enabled) {
            throw new IllegalArgumentException("不能停用当前管理员账户");
        }
        AccountEntity account = accountMapper.findByUsername(username);
        if (account == null) throw new IllegalArgumentException("账户不存在");
        if (account.isEnabled() == enabled) return;
        if (accountMapper.updateEnabled(username, enabled) != 1) throw new IllegalStateException("账户状态更新失败");
        auditService.record(actor, enabled ? "ENABLE_ACCOUNT" : "DISABLE_ACCOUNT", "ACCOUNT", username,
                enabled ? "启用账户" : "停用账户", "{\"enabled\":" + account.isEnabled() + "}",
                "{\"enabled\":" + enabled + "}", null, null, null);
    }

    public List<SkillNamespaceEntity> namespaces(String actor) {
        requireAdmin(actor); return namespaceMapper.findAll();
    }

    public List<NamespaceMemberVO> members(String namespaceKey, String actor) {
        requireAdmin(actor); return namespaceMapper.findAllMembers(namespaceKey);
    }

    @Transactional
    public void addMember(String namespaceKey, MemberCommand command, String actor) {
        requireAdmin(actor);
        validateMember(command);
        int changed = namespaceMapper.addMember(namespaceKey, command.getUsername(), command.getRoleKey(), actor);
        if (changed != 1) throw new IllegalArgumentException("命名空间或账户不存在");
        auditService.record(actor, "ADD_NAMESPACE_MEMBER", "SKILL_NAMESPACE", namespaceKey,
                "新增命名空间成员", "{}", "{\"username\":\"" + command.getUsername() + "\",\"role\":\""
                        + command.getRoleKey() + "\"}", null, null, null);
    }

    @Transactional
    public void updateMember(Long memberId, MemberCommand command, String actor) {
        requireAdmin(actor); validateMember(command);
        if (namespaceMapper.updateMemberRole(memberId, command.getRoleKey()) != 1) {
            throw new IllegalArgumentException("成员不存在");
        }
        auditService.record(actor, "UPDATE_NAMESPACE_MEMBER", "SKILL_NAMESPACE_MEMBER", String.valueOf(memberId),
                "调整命名空间成员角色", "{}", "{\"role\":\"" + command.getRoleKey() + "\"}", null, null, null);
    }

    @Transactional
    public void removeMember(Long memberId, String actor) {
        requireAdmin(actor);
        if (namespaceMapper.removeMember(memberId) != 1) throw new IllegalArgumentException("成员不存在");
        auditService.record(actor, "REMOVE_NAMESPACE_MEMBER", "SKILL_NAMESPACE_MEMBER", String.valueOf(memberId),
                "移除命名空间成员", "{}", "{\"removed\":true}", null, null, null);
    }

    public List<SkillVersionTagEntity> tags(String actor) {
        requireAdmin(actor); return tagMapper.findAll();
    }

    @Transactional
    public void removeTag(Long id, String actor) {
        requireAdmin(actor);
        if (tagMapper.delete(id) != 1) throw new IllegalArgumentException("标签不存在");
        auditService.record(actor, "REMOVE_VERSION_TAG", "SKILL_VERSION_TAG", String.valueOf(id),
                "移除版本标签", "{}", "{\"removed\":true}", null, null, null);
    }

    private void requireAdmin(String actor) { authorizationService.requireAnyRole(actor, "GOVERNANCE_ADMIN"); }
    private void validateMember(MemberCommand command) {
        if (command == null || command.getUsername() == null || command.getUsername().trim().isEmpty()
                || command.getRoleKey() == null || !command.getRoleKey().matches("OWNER|ADMIN|MEMBER")) {
            throw new IllegalArgumentException("成员信息不完整");
        }
    }
}
