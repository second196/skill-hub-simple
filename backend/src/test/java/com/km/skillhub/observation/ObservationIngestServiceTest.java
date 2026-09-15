package com.km.skillhub.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationIngestServiceTest {

    @Test
    void rollsUpCompositeChildrenToPlatformParents() {
        List<String> slugs = new ArrayList<String>();
        ObservationRepository repository = stubRepository(slugs, platformParentsOnly());
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> body = bodyWithSteps(
                step("skill", "sop-requirement", "sop-requirement"),
                step("skill", "using-product-development", "using-product-development"),
                step("skill", "brainstorming", "brainstorming"),
                step("skill", "superpowers", "superpowers"),
                step("skill", "local-only-skill", "local-only-skill")
        );

        Map<String, Object> result = service.ingest(body);
        assertEquals(Integer.valueOf(5), result.get("stepCount"));
        // sop-* rolls up to platform parent UPD
        assertEquals("using-product-development", slugs.get(0));
        assertEquals("using-product-development", slugs.get(1));
        // Unlisted composite child keeps no own slug; parent rollup step keeps superpowers
        assertEquals(null, slugs.get(2));
        assertEquals("superpowers", slugs.get(3));
        // Local-only skill is not uploaded as a platform observation card
        assertEquals(null, slugs.get(4));
    }

    @Test
    void keepsIndependentPlatformChildSkillSlug() {
        List<String> slugs = new ArrayList<String>();
        Map<String, String> platform = platformParentsOnly();
        platform.put("brainstorming", "brainstorming");
        ObservationRepository repository = stubRepository(slugs, platform);
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> body = bodyWithSteps(
                step("skill", "brainstorming", "brainstorming"),
                step("skill", "superpowers", "superpowers")
        );

        service.ingest(body);
        assertEquals("brainstorming", slugs.get(0));
        assertEquals("superpowers", slugs.get(1));
    }

    @Test
    void stillMapsPlatformAliases() {
        List<String> slugs = new ArrayList<String>();
        Map<String, String> platform = new LinkedHashMap<String, String>();
        platform.put("using-product-development", "Using Product Development");
        ObservationRepository repository = stubRepository(slugs, platform);
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> body = bodyWithSteps(
                step("skill", null, "Using Product Development")
        );
        service.ingest(body);
        assertEquals("using-product-development", slugs.get(0));
        assertTrue(slugs.get(0) != null);
    }

    private Map<String, String> platformParentsOnly() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("using-product-development", "using-product-development");
        map.put("superpowers", "superpowers");
        map.put("ui-ux-pro-max", "ui-ux-pro-max");
        return map;
    }

    private ObservationRepository stubRepository(final List<String> slugs, final Map<String, String> platform) {
        return new ObservationRepository(null, new ObjectMapper()) {
            @Override
            public Map<String, String> platformSkills() {
                return platform;
            }

            @Override
            public long upsertClient(String clientId, String hostname, String os, String meta) {
                return 1L;
            }

            @Override
            public long upsertSession(long clientRowId, String sessionKey, String clientName, java.time.Instant startedAt, java.time.Instant endedAt) {
                return 2L;
            }

            @Override
            public long upsertTurn(long sessionId, int turnIndex, java.time.Instant startedAt, String userText) {
                return 3L;
            }

            @Override
            public void upsertStep(long turnId, String stepId, int seq, String type, java.time.Instant ts, String skillSlug, String payloadJson) {
                slugs.add(skillSlug);
            }

            @Override
            public void saveBatch(String batchId, String clientId, int sessions, int turns, int steps, int skipped) {
            }
        };
    }

    @SafeVarargs
    private final Map<String, Object> bodyWithSteps(Map<String, Object>... steps) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        Map<String, Object> client = new LinkedHashMap<String, Object>();
        client.put("clientId", "c1");
        body.put("client", client);
        Map<String, Object> session = new LinkedHashMap<String, Object>();
        session.put("sessionId", "s1");
        session.put("clientName", "codex");
        Map<String, Object> turn = new LinkedHashMap<String, Object>();
        turn.put("turnIndex", Integer.valueOf(1));
        List<Object> list = new ArrayList<Object>();
        Collections.addAll(list, steps);
        turn.put("steps", list);
        session.put("turns", Collections.singletonList(turn));
        body.put("sessions", Collections.singletonList(session));
        return body;
    }

    private Map<String, Object> step(String type, String skillSlug, String skillName) {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("type", type);
        if (skillSlug != null) step.put("skillSlug", skillSlug);
        step.put("skillName", skillName);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", skillName);
        step.put("payload", payload);
        return step;
    }
}
