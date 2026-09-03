package com.km.skillhub.governance.controller;

import com.km.skillhub.governance.model.dto.LoginRequest;
import com.km.skillhub.governance.model.vo.SessionVO;
import com.km.skillhub.governance.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;

@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/login")
    public ResponseEntity<SessionVO> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        String username = sessionService.login(request, servletRequest);
        return ResponseEntity.ok(new SessionVO(true, username));
    }

    @GetMapping("/current")
    public ResponseEntity<SessionVO> current(Authentication authentication) {
        return ResponseEntity.ok(new SessionVO(authentication != null, authentication == null ? null : authentication.getName()));
    }

    @GetMapping("/csrf")
    public ResponseEntity<String> csrf(CsrfToken token) {
        return ResponseEntity.ok(token == null ? "" : token.getToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest servletRequest) {
        if (servletRequest.getSession(false) != null) {
            servletRequest.getSession(false).invalidate();
        }
        return ResponseEntity.noContent().build();
    }
}
