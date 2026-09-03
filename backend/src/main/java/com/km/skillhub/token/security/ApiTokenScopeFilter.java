package com.km.skillhub.token.security;

import com.km.skillhub.token.service.ApiTokenService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class ApiTokenScopeFilter extends OncePerRequestFilter {
    private final ApiTokenService apiTokenService;

    public ApiTokenScopeFilter(ApiTokenService apiTokenService) {
        this.apiTokenService = apiTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!ApiTokenAuthenticationFilter.hasBearerToken(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ApiTokenAuthentication)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Bearer Token 未通过认证");
            return;
        }
        String requiredScope = requiredScope(request);
        if (requiredScope == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Token 不支持访问此接口");
            return;
        }
        if (!apiTokenService.supportsScope((ApiTokenAuthentication) authentication, requiredScope)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Token 缺少所需作用域");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String requiredScope(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.equals("/api/v1/tokens") || path.startsWith("/api/v1/tokens/")) {
            return "token:manage";
        }
        if (path.equals("/api/v1/assets") || path.startsWith("/api/v1/assets/")) {
            if ("GET".equalsIgnoreCase(method)) {
                return "skill:read";
            }
            if ("POST".equalsIgnoreCase(method)
                    && (path.equals("/api/v1/assets/imports") || path.equals("/api/v1/assets/imports/package"))) {
                return "skill:publish";
            }
        }
        return null;
    }
}
