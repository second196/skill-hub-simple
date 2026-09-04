package com.km.skillhub.token;

import com.km.skillhub.token.security.ApiTokenAuthentication;
import com.km.skillhub.token.security.ApiTokenScopeFilter;
import com.km.skillhub.token.service.ApiTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiTokenScopeFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsAuthenticatedBearerToReadCurrentIdentityWithoutBusinessScope() throws Exception {
        ApiTokenService service = mock(ApiTokenService.class);
        ApiTokenScopeFilter filter = new ApiTokenScopeFilter(service);
        SecurityContextHolder.getContext().setAuthentication(new ApiTokenAuthentication(
                "admin", 1L, Collections.singletonList(new SimpleGrantedAuthority("SCOPE_telemetry:write"))));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session/current");
        request.addHeader("Authorization", "Bearer sk_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void requiresPublishScopeForPackageValidation() throws Exception {
        ApiTokenService service = mock(ApiTokenService.class);
        when(service.supportsScope(any(ApiTokenAuthentication.class), eq("skill:publish"))).thenReturn(true);
        ApiTokenScopeFilter filter = new ApiTokenScopeFilter(service);
        SecurityContextHolder.getContext().setAuthentication(new ApiTokenAuthentication(
                "admin", 1L, Collections.singletonList(new SimpleGrantedAuthority("SCOPE_skill:publish"))));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/assets/imports/package/validate");
        request.addHeader("Authorization", "Bearer sk_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void allowsPublishScopeToSubmitReview() throws Exception {
        ApiTokenService service = mock(ApiTokenService.class);
        when(service.supportsScope(any(ApiTokenAuthentication.class), eq("skill:publish"))).thenReturn(true);
        ApiTokenScopeFilter filter = new ApiTokenScopeFilter(service);
        SecurityContextHolder.getContext().setAuthentication(new ApiTokenAuthentication(
                "admin", 1L, Collections.singletonList(new SimpleGrantedAuthority("SCOPE_skill:publish"))));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews");
        request.addHeader("Authorization", "Bearer sk_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void requiresTelemetryScopeForRuntimeIntegrationEvents() throws Exception {
        ApiTokenService service = mock(ApiTokenService.class);
        when(service.supportsScope(any(ApiTokenAuthentication.class), eq("telemetry:write"))).thenReturn(true);
        ApiTokenScopeFilter filter = new ApiTokenScopeFilter(service);
        SecurityContextHolder.getContext().setAuthentication(new ApiTokenAuthentication(
                "admin", 1L, Collections.singletonList(new SimpleGrantedAuthority("SCOPE_telemetry:write"))));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/runtime-integrations/integration-1/events");
        request.addHeader("Authorization", "Bearer sk_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }
}
