package com.km.skillhub.admin.controller;

import com.km.skillhub.admin.model.AccountAdminVO;
import com.km.skillhub.admin.model.MemberCommand;
import com.km.skillhub.admin.service.AdminGovernanceService;
import com.km.skillhub.namespace.model.entity.SkillNamespaceEntity;
import com.km.skillhub.namespace.model.vo.NamespaceMemberVO;
import com.km.skillhub.version.model.entity.SkillVersionTagEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminGovernanceController {
    private final AdminGovernanceService service;

    public AdminGovernanceController(AdminGovernanceService service) { this.service = service; }

    @GetMapping("/accounts")
    public ResponseEntity<List<AccountAdminVO>> accounts(Authentication authentication) {
        return ResponseEntity.ok(service.accounts(authentication.getName()));
    }

    @PostMapping("/accounts/{username}/status")
    public ResponseEntity<Void> accountStatus(@PathVariable String username, @RequestParam boolean enabled,
                                              Authentication authentication) {
        service.setAccountEnabled(username, enabled, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/namespaces")
    public ResponseEntity<List<SkillNamespaceEntity>> namespaces(Authentication authentication) {
        return ResponseEntity.ok(service.namespaces(authentication.getName()));
    }

    @GetMapping("/namespaces/{namespaceKey}/members")
    public ResponseEntity<List<NamespaceMemberVO>> members(@PathVariable String namespaceKey,
                                                            Authentication authentication) {
        return ResponseEntity.ok(service.members(namespaceKey, authentication.getName()));
    }

    @PostMapping("/namespaces/{namespaceKey}/members")
    public ResponseEntity<Void> addMember(@PathVariable String namespaceKey, @RequestBody MemberCommand command,
                                          Authentication authentication) {
        service.addMember(namespaceKey, command, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{memberId}/role")
    public ResponseEntity<Void> updateMember(@PathVariable Long memberId, @RequestBody MemberCommand command,
                                             Authentication authentication) {
        service.updateMember(memberId, command, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long memberId, Authentication authentication) {
        service.removeMember(memberId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/labels")
    public ResponseEntity<List<SkillVersionTagEntity>> labels(Authentication authentication) {
        return ResponseEntity.ok(service.tags(authentication.getName()));
    }

    @DeleteMapping("/labels/{id}")
    public ResponseEntity<Void> removeLabel(@PathVariable Long id, Authentication authentication) {
        service.removeTag(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
