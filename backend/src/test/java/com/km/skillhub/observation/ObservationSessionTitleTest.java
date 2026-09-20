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

    @Test
    void mergesOnlyExactClonesAndKeepsSameTitleDifferentSessions() {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        Map<String, Object> stub = new HashMap<String, Object>();
        stub.put("id", Integer.valueOf(1));
        stub.put("client_id", "c1");
        stub.put("client_name", "codex");
        stub.put("started_at", "2026-06-29T06:18:16.849+00:00");
        stub.put("turn_count", Integer.valueOf(1));
        stub.put("title", "启动项目");
        stub.put("session_key", "codex:019f1207");
        stub.put("token_total", Long.valueOf(10L));
        Map<String, Object> main = new HashMap<String, Object>();
        main.put("id", Integer.valueOf(2));
        main.put("client_id", "c1");
        main.put("client_name", "codex");
        main.put("started_at", "2026-06-29T08:34:36.986+00:00");
        main.put("turn_count", Integer.valueOf(52));
        main.put("title", "启动项目");
        main.put("session_key", "codex:019f1283");
        main.put("token_total", Long.valueOf(1000L));
        Map<String, Object> clone = new HashMap<String, Object>(main);
        clone.put("id", Integer.valueOf(3));
        clone.put("session_key", "codex:019f1283-clone");
        rows.add(stub);
        rows.add(main);
        rows.add(clone);

        java.util.List<Map<String, Object>> merged = ObservationRepository.mergeSessionRowsForDisplay(rows);
        // Same title + different start time/session stay separate; only the true clone collapses.
        assertEquals(2, merged.size());
        assertEquals(Integer.valueOf(1), merged.get(0).get("id"));
        assertEquals("启动项目", merged.get(0).get("title"));
        assertEquals(Integer.valueOf(2), merged.get(1).get("id"));
        assertEquals("启动项目", merged.get(1).get("title"));
        assertEquals(Long.valueOf(2000L), merged.get(1).get("token_total"));
    }
}
