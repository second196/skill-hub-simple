package com.km.skillhub.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationIngestServiceTest {

    @Test
    void rollsUpCompositeChildrenToPlatformParents() {
        List<String> slugs = new ArrayList<String>();
        ObservationRepository repository = stubRepository(slugs, versionLists(), platformParentsOnly(), publishedAll("1.0.0"));
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> body = bodyWithSteps(
                step("skill", "sop-requirement", "sop-requirement", "1.0.0"),
                step("skill", "using-product-development", "using-product-development", "1.0.0"),
                step("skill", "brainstorming", "brainstorming", "1.0.0"),
                step("skill", "superpowers", "superpowers", "1.0.0"),
                step("skill", "local-only-skill", "local-only-skill", "1.0.0")
        );

        Map<String, Object> result = service.ingest(body);
        // Local and unpublished identities are retained; platform parent mapping still applies.
        assertEquals(Integer.valueOf(5), result.get("stepCount"));
        assertEquals("using-product-development", slugs.get(0));
        assertEquals("using-product-development", slugs.get(1));
        assertEquals("brainstorming", slugs.get(2));
        assertEquals("superpowers", slugs.get(3));
        assertEquals("local-only-skill", slugs.get(4));
        assertEquals(Integer.valueOf(0), result.get("skippedStepCount"));
    }

    @Test
    void keepsIndependentPlatformChildSkillSlug() {
        List<String> slugs = new ArrayList<String>();
        Map<String, String> platform = platformParentsOnly();
        platform.put("brainstorming", "brainstorming");
        ObservationRepository repository = stubRepository(slugs, versionLists(), platform, publishedAll("1.0.0"));
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> body = bodyWithSteps(
                step("skill", "brainstorming", "brainstorming", "1.0.0"),
                step("skill", "superpowers", "superpowers", "1.0.0")
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
        ObservationRepository repository = stubRepository(slugs, versionLists(), platform, publishedAll("2.1.0"));
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> body = bodyWithSteps(
                step("skill", null, "Using Product Development", "2.1.0")
        );
        service.ingest(body);
        assertEquals("using-product-development", slugs.get(0));
        assertTrue(slugs.get(0) != null);
    }

    @Test
    void writesVersionLabelOnlyAndIgnoresDigest() {
        List<String> slugs = new ArrayList<String>();
        Map<String, List<String>> versions = versionLists();
        Map<String, String> platform = new LinkedHashMap<String, String>();
        platform.put("using-product-development", "using-product-development");
        Map<String, Set<String>> published = new LinkedHashMap<String, Set<String>>();
        Set<String> labels = new HashSet<String>();
        labels.add("1.2.0");
        labels.add("1.3.0");
        labels.add("2.0.0");
        published.put("using-product-development", labels);
        ObservationRepository repository = stubRepository(slugs, versions, platform, published);
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> skillStep = step("skill", "using-product-development", "using-product-development", "1.2.0");
        skillStep.put("skillVersionLabel", "1.2.0");
        skillStep.put("skillVersionSource", "observed");

        Map<String, Object> snakeStep = step("skill", "using-product-development", "using-product-development", "v1.3.0");
        snakeStep.put("skill_version_label", "1.3.0");

        Map<String, Object> payloadStep = step("skill", "using-product-development", "using-product-development", null);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", "using-product-development");
        payload.put("version_label", "2.0.0");
        payloadStep.put("payload", payload);

        Map<String, Object> result = service.ingest(bodyWithSteps(skillStep, snakeStep, payloadStep));
        assertEquals(Integer.valueOf(3), result.get("stepCount"));

        assertEquals("1.2.0", versions.get("label").get(0));
        assertEquals("observed", versions.get("source").get(0));

        assertEquals("1.3.0", versions.get("label").get(1));
        assertEquals("observed", versions.get("source").get(1));

        assertEquals("2.0.0", versions.get("label").get(2));
        assertEquals("observed", versions.get("source").get(2));
    }

    @Test
    void keepsMismatchedAndUnversionedLocalSkillSteps() {
        List<String> slugs = new ArrayList<String>();
        Map<String, List<String>> versions = versionLists();
        ObservationRepository repository = stubRepository(slugs, versions, platformParentsOnly(), publishedAll("1.0.0"));
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> user = step("user", null, null, null);
        Map<String, Object> versioned = step("skill", "using-product-development", "using-product-development", "1.0.0");
        Map<String, Object> unversioned = step("skill", "superpowers", "superpowers", null);
        Map<String, Object> mismatched = step("skill", "superpowers", "superpowers", "6.0.3");

        Map<String, Object> result = service.ingest(bodyWithSteps(user, versioned, unversioned, mismatched));
        assertEquals(Integer.valueOf(4), result.get("stepCount"));
        assertEquals(Integer.valueOf(1), result.get("sessionCount"));
        assertEquals(Integer.valueOf(0), result.get("skippedStepCount"));
        List<String> skillSlugs = new ArrayList<String>();
        for (String slug : slugs) {
            if (slug != null) skillSlugs.add(slug);
        }
        assertEquals(3, skillSlugs.size());
        assertEquals("using-product-development", skillSlugs.get(0));
        assertEquals("superpowers", skillSlugs.get(1));
        assertEquals("superpowers", skillSlugs.get(2));
    }

    @Test
    void allowsMultiplePublishedVersions() {
        List<String> slugs = new ArrayList<String>();
        Map<String, List<String>> versions = versionLists();
        Map<String, String> platform = new LinkedHashMap<String, String>();
        platform.put("using-product-development", "using-product-development");
        Map<String, Set<String>> published = new LinkedHashMap<String, Set<String>>();
        Set<String> labels = new HashSet<String>();
        labels.add("1.0.0");
        labels.add("1.0.1");
        published.put("using-product-development", labels);
        ObservationRepository repository = stubRepository(slugs, versions, platform, published);
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> result = service.ingest(bodyWithSteps(
                step("skill", "using-product-development", "using-product-development", "1.0.0"),
                step("skill", "using-product-development", "using-product-development", "v1.0.1"),
                step("skill", "using-product-development", "using-product-development", "9.9.9")
        ));
        assertEquals(Integer.valueOf(3), result.get("stepCount"));
        assertEquals("1.0.0", versions.get("label").get(0));
        assertEquals("1.0.1", versions.get("label").get(1));
        assertEquals("9.9.9", versions.get("label").get(2));
    }

    @Test
    void acceptsSessionWhenAllSkillStepsHaveSemVer() {
        List<String> slugs = new ArrayList<String>();
        Map<String, List<String>> versions = versionLists();
        ObservationRepository repository = stubRepository(slugs, versions, platformParentsOnly(), publishedAll("1.0.0"));
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> result = service.ingest(bodyWithSteps(
                step("user", null, null, null),
                step("skill", "using-product-development", "using-product-development", "1.0.0")
        ));
        assertEquals(Integer.valueOf(2), result.get("stepCount"));
        assertEquals(Integer.valueOf(1), result.get("sessionCount"));
    }

    @Test
    void skipsSessionWithoutPlatformSkill() {
        List<String> slugs = new ArrayList<String>();
        Map<String, List<String>> versions = versionLists();
        ObservationRepository repository = stubRepository(slugs, versions, platformParentsOnly(), publishedAll("1.0.0"));
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> result = service.ingest(bodyWithSteps(
                step("skill", "local-only-skill", "local-only-skill", "v2.3.4")
        ));
        assertEquals(Integer.valueOf(0), result.get("stepCount"));
        assertEquals(Integer.valueOf(0), result.get("sessionCount"));
        assertEquals(Integer.valueOf(1), result.get("skippedStepCount"));
        assertTrue(slugs.isEmpty());
    }

    @Test
    void skipsPlatformSkillWhenVersionIsMissingOrUnpublished() {
        List<String> slugs = new ArrayList<String>();
        Map<String, List<String>> versions = versionLists();
        ObservationRepository repository = stubRepository(slugs, versions, platformParentsOnly(), publishedAll("1.0.0"));
        ObservationIngestService service = new ObservationIngestService(repository, new ObjectMapper());

        Map<String, Object> unversioned = service.ingest(bodyWithSteps(
                step("skill", "using-product-development", "using-product-development", null)
        ));
        assertEquals(Integer.valueOf(0), unversioned.get("sessionCount"));

        Map<String, Object> unpublished = service.ingest(bodyWithSteps(
                step("skill", "using-product-development", "using-product-development", "9.9.9")
        ));
        assertEquals(Integer.valueOf(0), unpublished.get("sessionCount"));
        assertTrue(slugs.isEmpty());
    }

    private Map<String, List<String>> versionLists() {
        Map<String, List<String>> map = new LinkedHashMap<String, List<String>>();
        map.put("label", new ArrayList<String>());
        map.put("source", new ArrayList<String>());
        return map;
    }

    private Map<String, String> platformParentsOnly() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("using-product-development", "using-product-development");
        map.put("superpowers", "superpowers");
        map.put("ui-ux-pro-max", "ui-ux-pro-max");
        return map;
    }

    private Map<String, Set<String>> publishedAll(String... labels) {
        Map<String, Set<String>> map = new LinkedHashMap<String, Set<String>>();
        for (String slug : new String[] {"using-product-development", "superpowers", "ui-ux-pro-max", "brainstorming"}) {
            Set<String> set = new HashSet<String>();
            Collections.addAll(set, labels);
            map.put(slug, set);
        }
        return map;
    }

    private ObservationRepository stubRepository(final List<String> slugs,
                                                 final Map<String, List<String>> versions,
                                                 final Map<String, String> platform,
                                                 final Map<String, Set<String>> publishedVersions) {
        final List<String> versionLabels = versions.get("label");
        final List<String> versionSources = versions.get("source");
        return new ObservationRepository(null, new ObjectMapper()) {
            @Override
            public Map<String, String> platformSkills() {
                return platform;
            }

            @Override
            public Map<String, Set<String>> publishedVersionsBySlug() {
                return publishedVersions;
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
            public long upsertSession(long clientRowId, String sessionKey, String clientName, java.time.Instant startedAt, java.time.Instant endedAt, String title) {
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
            public void upsertStep(long turnId, String stepId, int seq, String type, java.time.Instant ts, String skillSlug, String payloadJson,
                                   String skillVersionLabel, String skillVersionSource) {
                slugs.add(skillSlug);
                versionLabels.add(skillVersionLabel);
                versionSources.add(skillVersionSource);
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

    private Map<String, Object> step(String type, String skillSlug, String skillName, String versionLabel) {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("type", type);
        if (skillSlug != null) step.put("skillSlug", skillSlug);
        if (skillName != null) step.put("skillName", skillName);
        if (versionLabel != null) step.put("skillVersionLabel", versionLabel);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (skillName != null) payload.put("name", skillName);
        if (versionLabel != null) payload.put("skill_version_label", versionLabel);
        step.put("payload", payload);
        return step;
    }
}
