package com.km.skillhub.token.controller;

import com.km.skillhub.token.model.ApiTokenCreateRequest;
import com.km.skillhub.token.model.ApiTokenCreateResponse;
import com.km.skillhub.token.model.ApiTokenSummary;
import com.km.skillhub.token.model.TokenExpirationUpdateRequest;
import com.km.skillhub.token.service.ApiTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tokens")
public class ApiTokenController {
    private final ApiTokenService apiTokenService;

    public ApiTokenController(ApiTokenService apiTokenService) {
        this.apiTokenService = apiTokenService;
    }

    @PostMapping
    public ResponseEntity<ApiTokenCreateResponse> create(@RequestBody ApiTokenCreateRequest request,
                                                         Authentication authentication) {
        return ResponseEntity.ok(apiTokenService.create(authentication.getName(), request));
    }

    @GetMapping
    public ResponseEntity<List<ApiTokenSummary>> list(Authentication authentication) {
        return ResponseEntity.ok(apiTokenService.list(authentication.getName()));
    }

    @PutMapping("/{id}/expiration")
    public ResponseEntity<ApiTokenSummary> updateExpiration(@PathVariable Long id,
                                                            @RequestBody TokenExpirationUpdateRequest request,
                                                            Authentication authentication) {
        return ResponseEntity.ok(apiTokenService.updateExpiration(authentication.getName(), id,
                request == null ? null : request.getExpiresAt()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id, Authentication authentication) {
        apiTokenService.revoke(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
