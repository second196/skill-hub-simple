package com.km.skillhub.config;

import com.km.skillhub.governance.model.entity.AccountEntity;
import com.km.skillhub.mapper.governance.AccountMapper;
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
public class AccountSessionValidityFilter extends OncePerRequestFilter {
    private final AccountMapper accountMapper;

    public AccountSessionValidityFilter(AccountMapper accountMapper) { this.accountMapper = accountMapper; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            AccountEntity account = accountMapper.findByUsername(authentication.getName());
            if (account == null || !account.isEnabled()) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "账户已停用");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
