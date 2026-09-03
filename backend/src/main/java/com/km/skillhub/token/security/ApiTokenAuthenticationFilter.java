package com.km.skillhub.token.security;

import com.km.skillhub.token.service.ApiTokenService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {
    private final ApiTokenService apiTokenService;

    public ApiTokenAuthenticationFilter(ApiTokenService apiTokenService) {
        this.apiTokenService = apiTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!isBearer(authorization)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "认证方式不受支持");
            return;
        }
        String rawToken = authorization.substring("Bearer ".length()).trim();
        ApiTokenAuthentication authentication = apiTokenService.authenticate(rawToken);
        if (authentication == null) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token 无效、已过期或已撤销");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    public static boolean isBearer(String authorization) {
        return authorization != null && authorization.length() > 7
                && authorization.regionMatches(true, 0, "Bearer", 0, 6)
                && Character.isWhitespace(authorization.charAt(6));
    }

    public static boolean hasBearerToken(HttpServletRequest request) {
        return isBearer(request.getHeader("Authorization"));
    }
}
