package com.km.skillhub.observation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retention purge integration tests against the local PG test database
 * (skillhub_t10_test). Fixtures use unique client ids so concurrent runs and
 * pre-existing rows never collide; expired fixtures are always inserted inside
 * the test method that purges them.
 */
class ObservationRetentionServiceTest {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/skillhub_t10_test";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "123456";

    private static JdbcTemplate jdbc;

    private String clientId;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource ds = new DriverManagerDataSource(DB_URL, DB_USER, DB_PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        // Fail fast with a clear message when the local PG test DB is missing.
        jdbc.queryForObject("SELECT count(*) FROM observation_session", Integer.class);
    }

    @AfterAll
    static void removeFixtures() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM observation_client WHERE client_id LIKE 'retention-test-%'");
        }
    }

    @BeforeEach
    void newClientId() {
        clientId = "retention-test-" + UUID.randomUUID();
    }

    @Test
    void deletesExpiredDataWhenEnabled() {
        long clientRowId = insertClient();
        // Expired session (200d old) with an expired step (100d old).
        long expiredSession = insertSession(clientRowId, "expired-" + UUID.randomUUID(), daysAgo(200));
        long expiredTurn = insertTurn(expiredSession, 1, daysAgo(200));
        insertStep(expiredTurn, "expired-step", 1, daysAgo(100));
        // Fresh session holding one fresh step and one expired step.
        long freshSession = insertSession(clientRowId, "fresh-" + UUID.randomUUID(), daysAgo(1));
        long freshTurn = insertTurn(freshSession, 1, daysAgo(1));
        insertStep(freshTurn, "fresh-step", 1, daysAgo(1));
        insertStep(freshTurn, "stale-step", 2, daysAgo(100));

        service(true, 90, 180).purgeExpired();

        assertEquals(0, count("SELECT count(*) FROM observation_session WHERE id=?", expiredSession),
                "expired session must be deleted");
        assertEquals(0, count("SELECT count(*) FROM observation_turn WHERE session_id=?", expiredSession),
                "expired turns must cascade with the session");
        assertEquals(0, count("SELECT count(*) FROM observation_step WHERE turn_id=?", expiredTurn),
                "expired steps must cascade with the turn");
        assertEquals(1, count("SELECT count(*) FROM observation_session WHERE id=?", freshSession),
                "unexpired session must survive");
        assertEquals(1, count("SELECT count(*) FROM observation_turn WHERE session_id=?", freshSession),
                "unexpired turn must survive");
        assertEquals(1, count("SELECT count(*) FROM observation_step WHERE turn_id=? AND step_id='fresh-step'", freshTurn),
                "unexpired step must survive");
        assertEquals(0, count("SELECT count(*) FROM observation_step WHERE turn_id=? AND step_id='stale-step'", freshTurn),
                "step older than step-days must be deleted even inside a live session");
    }

    @Test
    void keepsUnexpiredDataWhenEnabled() {
        long clientRowId = insertClient();
        long session = insertSession(clientRowId, "keep-" + UUID.randomUUID(), daysAgo(10));
        long turn = insertTurn(session, 1, daysAgo(10));
        insertStep(turn, "keep-step", 1, daysAgo(10));

        service(true, 90, 180).purgeExpired();

        assertEquals(1, count("SELECT count(*) FROM observation_session WHERE id=?", session));
        assertEquals(1, count("SELECT count(*) FROM observation_turn WHERE id=?", turn));
        assertEquals(1, count("SELECT count(*) FROM observation_step WHERE turn_id=? AND step_id='keep-step'", turn));
    }

    @Test
    void keepsExpiredDataWhenDisabledByDefault() {
        long clientRowId = insertClient();
        long session = insertSession(clientRowId, "off-" + UUID.randomUUID(), daysAgo(300));
        long turn = insertTurn(session, 1, daysAgo(300));
        insertStep(turn, "off-step", 1, daysAgo(300));

        service(false, 90, 180).purgeExpired();

        assertEquals(1, count("SELECT count(*) FROM observation_session WHERE id=?", session),
                "disabled retention must not delete anything");
        assertEquals(1, count("SELECT count(*) FROM observation_turn WHERE id=?", turn));
        assertEquals(1, count("SELECT count(*) FROM observation_step WHERE turn_id=? AND step_id='off-step'", turn));
    }

    @Test
    void zeroRetentionDaysMeansNoCleanup() {
        long clientRowId = insertClient();
        long session = insertSession(clientRowId, "zero-" + UUID.randomUUID(), daysAgo(300));
        long turn = insertTurn(session, 1, daysAgo(300));
        insertStep(turn, "zero-step", 1, daysAgo(300));

        service(true, 0, 0).purgeExpired();

        assertEquals(1, count("SELECT count(*) FROM observation_session WHERE id=?", session),
                "day count 0 must skip that tier entirely");
        assertEquals(1, count("SELECT count(*) FROM observation_step WHERE turn_id=? AND step_id='zero-step'", turn));
    }

    @Test
    @SuppressWarnings("unchecked")
    void applicationYmlDefaultsToDisabled() {
        InputStream in = ObservationRetentionServiceTest.class.getResourceAsStream("/application.yml");
        assertNotNull(in, "application.yml must be on the test classpath");
        Map<String, Object> root = new Yaml().load(in);
        Map<String, Object> skillhub = (Map<String, Object>) root.get("skillhub");
        Map<String, Object> observation = (Map<String, Object>) skillhub.get("observation");
        Map<String, Object> retention = (Map<String, Object>) observation.get("retention");
        assertEquals("${SKILLHUB_OBSERVATION_RETENTION_ENABLED:false}", retention.get("enabled"),
                "yml default must resolve to enabled=false");
        assertEquals("${SKILLHUB_OBSERVATION_RETENTION_STEP_DAYS:90}", retention.get("step-days"));
        assertEquals("${SKILLHUB_OBSERVATION_RETENTION_SESSION_DAYS:180}", retention.get("session-days"));
        assertEquals("${SKILLHUB_OBSERVATION_RETENTION_CRON:0 30 4 * * ?}", retention.get("cron"));
    }

    private ObservationRetentionService service(boolean enabled, int stepDays, int sessionDays) {
        return new ObservationRetentionService(new ObservationRetentionRepository(jdbc), enabled, stepDays, sessionDays);
    }

    private static Timestamp daysAgo(int days) {
        return Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private long insertClient() {
        Long id = jdbc.queryForObject(
                "INSERT INTO observation_client (client_id, hostname, os) VALUES (?, 'retention-host', 'test') RETURNING id",
                Long.class, clientId);
        assertNotNull(id);
        return id;
    }

    private long insertSession(long clientRowId, String sessionKey, Timestamp startedAt) {
        Long id = jdbc.queryForObject(
                "INSERT INTO observation_session (client_row_id, session_key, client_name, started_at) " +
                        "VALUES (?, ?, 'codex', ?) RETURNING id",
                Long.class, clientRowId, sessionKey, startedAt);
        assertNotNull(id);
        return id;
    }

    private long insertTurn(long sessionId, int turnIndex, Timestamp startedAt) {
        Long id = jdbc.queryForObject(
                "INSERT INTO observation_turn (session_id, turn_index, started_at, user_text) VALUES (?, ?, ?, 'hi') RETURNING id",
                Long.class, sessionId, turnIndex, startedAt);
        assertNotNull(id);
        return id;
    }

    private void insertStep(long turnId, String stepId, int seq, Timestamp ts) {
        jdbc.update("INSERT INTO observation_step (turn_id, step_id, seq, type, ts, skill_slug, payload) " +
                "VALUES (?, ?, ?, 'skill', ?, 'retention-probe', '{}'::jsonb)", turnId, stepId, seq, ts);
    }
}
