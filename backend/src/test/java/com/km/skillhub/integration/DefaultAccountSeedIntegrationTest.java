package com.km.skillhub.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DefaultAccountSeedIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void seedsAdminAndUserAccountsWithExpectedRoles() {
        assertTrue(passwordEncoder.matches("admin123", passwordHash("admin")));
        assertTrue(passwordEncoder.matches("user123", passwordHash("user")));
        assertEquals(8, roleCount("admin"));
        assertEquals(1, roleCount("user"));
        assertEquals("GOVERNANCE_ADMIN", onlyRole("admin", "GOVERNANCE_ADMIN"));
        assertEquals("ASSET_CONTRIBUTOR", onlyRole("user", "ASSET_CONTRIBUTOR"));
    }

    @Test
    void logsInWithBothDefaultAccounts() throws Exception {
        login("admin", "admin123");
        login("user", "user123");
    }

    private void login(String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/session/login")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value(username));
    }

    private String passwordHash(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM principal_account WHERE username = ?",
                String.class, username);
    }

    private int roleCount(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM principal_scope_role psr "
                        + "JOIN principal_account pa ON pa.id = psr.principal_id "
                        + "WHERE pa.username = ?",
                Integer.class, username);
    }

    private String onlyRole(String username, String roleKey) {
        return jdbcTemplate.queryForObject(
                "SELECT psr.role_key FROM principal_scope_role psr "
                        + "JOIN principal_account pa ON pa.id = psr.principal_id "
                        + "WHERE pa.username = ? AND psr.role_key = ?",
                String.class, username, roleKey);
    }
}
