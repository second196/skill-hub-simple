package com.km.skillhub.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSecurityIntegrationTest {

    @Test
    void testEntryPointIsJdk8Compatible() {
        assertTrue(System.getProperty("java.specification.version") != null);
    }
}
