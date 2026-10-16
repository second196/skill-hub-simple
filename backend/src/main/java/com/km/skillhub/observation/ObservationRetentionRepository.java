package com.km.skillhub.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Write-side repository for observation retention deletes.
 *
 * <p>Deletion order follows the V4 foreign keys (step -> turn -> session, all
 * ON DELETE CASCADE): child rows are deleted first, expired sessions last so
 * cascades carry away turns, steps and the V16 summary rows keyed on
 * session_id.</p>
 */
@Repository
public class ObservationRetentionRepository {

    private final JdbcTemplate jdbc;

    public ObservationRetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** observation_step is a leaf table; direct delete never violates FKs. */
    public int deleteStepsBefore(Instant cutoff) {
        return jdbc.update(
                "DELETE FROM observation_step WHERE COALESCE(ts, created_at) < ?",
                Timestamp.from(cutoff));
    }

    /** Deleting a turn cascades to its steps (V4 turn_id ON DELETE CASCADE). */
    public int deleteTurnsBefore(Instant cutoff) {
        return jdbc.update(
                "DELETE FROM observation_turn WHERE COALESCE(started_at, created_at) < ?",
                Timestamp.from(cutoff));
    }

    /**
     * Deleting a session cascades to turns, steps and the V16 summary tables
     * (observation_session_summary / observation_skill_session_summary).
     * The OR form keeps the started_at branch sargable for
     * idx_observation_session_page (started_at DESC, id DESC).
     */
    public int deleteSessionsBefore(Instant cutoff) {
        Timestamp ts = Timestamp.from(cutoff);
        return jdbc.update(
                "DELETE FROM observation_session WHERE " +
                        "(started_at IS NOT NULL AND started_at < ?) OR " +
                        "(started_at IS NULL AND created_at < ?)",
                ts, ts);
    }
}
