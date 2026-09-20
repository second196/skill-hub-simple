package com.km.skillhub.observation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationSessionTitleTest {

    @Test
    void treatsInjectedContextAsNonTitle() {
        assertTrue(ObservationRepository.looksLikeSystemSessionText(
                "# AGENTS.md instructions for D:\\program\\skill-hub\\n<INSTRUCTIONS>"));
        assertTrue(ObservationRepository.looksLikeSystemSessionText(
                "<environment_context><cwd>D:\\program\\skill-hub-simple</cwd></environment_context>"));
        assertTrue(ObservationRepository.looksLikeSystemSessionText(
                "<ide_opened_file>The user opened the file d:\\program\\x</ide_opened_file>"));
        assertFalse(ObservationRepository.looksLikeSystemSessionText("帮我修复指标页会话下拉标题"));
        assertEquals("", ObservationRepository.cleanSessionTitleText(
                "# AGENTS.md instructions for D:\\program\\skill-hub"));
        assertEquals("帮我修复指标页会话下拉标题",
                ObservationRepository.cleanSessionTitleText("  帮我修复指标页会话下拉标题  "));
    }

    @Test
    void extractsProjectHintFromSystemContext() {
        assertEquals("skill-hub-simple",
                ObservationRepository.projectHintFromText(
                        "<environment_context><cwd>D:\\program\\skill-hub-simple</cwd></environment_context>"));
        assertEquals("kmplm-development-process",
                ObservationRepository.projectHintFromText(
                        "# AGENTS.md instructions for D:\\program\\kmplm-development-process\n<INSTRUCTIONS>"));
        assertEquals("program-skill-hub-simple",
                ObservationRepository.projectHintFromSessionKey("claude-code:d--program-skill-hub-simple/aaaaaaaa-bbbb"));
        assertEquals("",
                ObservationRepository.projectHintFromSessionKey("codex:019ed43f-5d45-7cb3-a417-cda7d9bfb6d5"));
    }

    @Test
    void prefersProjectHintThenClientDateFallback() {
        Map<String, Object> session = new HashMap<String, Object>();
        session.put("client_name", "codex");
        session.put("started_at", "2026-09-16T06:54:22Z");
        session.put("session_key", "codex:019ed43f-5d45-7cb3-a417-cda7d9bfb6d5");
        assertEquals("Codex · 2026-09-16", ObservationRepository.sessionTitleFallbackForTest(session));

        session.put("raw_title", "<environment_context><cwd>D:\\program\\skill-hub-simple</cwd></environment_context>");
        assertEquals("skill-hub-simple", ObservationRepository.sessionTitleFallbackForTest(session));
    }
}
