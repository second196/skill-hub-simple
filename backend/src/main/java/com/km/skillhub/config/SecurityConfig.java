package com.km.skillhub.config;

import com.km.skillhub.governance.service.AccountUserDetailsService;
import com.km.skillhub.token.security.ApiTokenAuthenticationFilter;
import com.km.skillhub.token.security.ApiTokenScopeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(
            AccountUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   DaoAuthenticationProvider authenticationProvider,
                                                   AccountSessionValidityFilter accountSessionValidityFilter,
                                                   ApiTokenAuthenticationFilter apiTokenAuthenticationFilter,
                                                   ApiTokenScopeFilter apiTokenScopeFilter) throws Exception {
        http.authenticationProvider(authenticationProvider)
                .csrf().csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers(new RequestMatcher() {
                    @Override
                    public boolean matches(javax.servlet.http.HttpServletRequest request) {
                        return ApiTokenAuthenticationFilter.hasBearerToken(request);
                    }
                })
                .and()
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .and()
                .addFilterBefore(apiTokenScopeFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore(accountSessionValidityFilter, ApiTokenScopeFilter.class)
                .addFilterBefore(apiTokenAuthenticationFilter, AccountSessionValidityFilter.class)
                .authorizeRequests()
                .antMatchers("/api/v1/session/login", "/api/v1/session/csrf", "/actuator/health", "/swagger-ui/**", "/v3/api-docs/**")
                .permitAll()
                .anyRequest().authenticated()
                .and()
                .logout()
                .logoutUrl("/api/v1/session/logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true);
        return http.build();
    }
}
