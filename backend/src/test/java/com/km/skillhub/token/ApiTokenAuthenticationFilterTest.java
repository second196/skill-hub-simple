package com.km.skillhub.token;

import com.km.skillhub.token.security.ApiTokenAuthentication;
import com.km.skillhub.token.security.ApiTokenAuthenticationFilter;
import com.km.skillhub.token.service.ApiTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import javax.servlet.FilterChain;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.context.SecurityContextHolder.clearContext;

class ApiTokenAuthenticationFilterTest {
    @AfterEach
    void clearSecurityContext() {
        clearContext();
    }

    @Test
    void rejectsUnknownBearerTokenWithUnauthorized() throws Exception {
        ApiTokenService service = mock(ApiTokenService.class);
        when(service.authenticate("sk_invalid")).thenReturn(null);
        ApiTokenAuthenticationFilter filter = new ApiTokenAuthenticationFilter(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer sk_invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void authenticatesBearerTokenAndPreservesScopeAuthorities() throws Exception {
        ApiTokenService service = mock(ApiTokenService.class);
        ApiTokenAuthentication authentication = new ApiTokenAuthentication("admin", 1L,
                Collections.singletonList(new SimpleGrantedAuthority("SCOPE_skill:read")));
        when(service.authenticate("sk_read")).thenReturn(authentication);
        ApiTokenAuthenticationFilter filter = new ApiTokenAuthenticationFilter(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer sk_read");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertTrue(org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getAuthorities().contains(new SimpleGrantedAuthority("SCOPE_skill:read")));
        verify(chain).doFilter(request, response);
    }
}
