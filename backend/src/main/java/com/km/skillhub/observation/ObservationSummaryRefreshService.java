package com.km.skillhub.observation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refresh trigger for the V16 read-summary tables (observation_*_summary).
 *
 * <p>Strategy — both, on purpose:</p>
 * <ul>
 *   <li><b>Async after ingest</b>: a successful POST /api/observations/ingest marks the
 *       summaries dirty and a single daemon worker runs
 *       {@code refresh_observation_read_summaries()} shortly after the ingest
 *       transaction commits. Triggers coalesce: N ingests in a burst produce at most
 *       one queued refresh (the flag is cleared only when a run starts).</li>
 *   <li><b>Scheduled fallback</b>: a fixed-delay job drains the same dirty flag, so a
 *       refresh that was never picked up (worker rejected/died, JVM hiccup) still runs
 *       without waiting for the next ingest. A failed refresh keeps the flag set, so
 *       the next scheduled pass retries it.</li>
 * </ul>
 *
 * <p>Reading the flag-only design keeps the expensive full rebuild off the ingest
 * request path while guaranteeing eventual consistency for the summary tables.</p>
 */
@Service
public class ObservationSummaryRefreshService {

    static final String REFRESH_SQL = "SELECT refresh_observation_read_summaries()";

    private static final Logger log = LoggerFactory.getLogger(ObservationSummaryRefreshService.class);

    private final JdbcTemplate jdbc;
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final ExecutorService executor;

    @Autowired
    public ObservationSummaryRefreshService(JdbcTemplate jdbc) {
        this(jdbc, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "observation-summary-refresh");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** Visible for tests: inject a deterministic executor (no background thread). */
    ObservationSummaryRefreshService(JdbcTemplate jdbc, ExecutorService executor) {
        this.jdbc = jdbc;
        this.executor = executor;
    }

    /** Mark summaries dirty and kick the async worker (coalesced). */
    public void requestReadSummaries() {
        pending.set(true);
        try {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    runPendingRefresh();
                }
            });
        } catch (RejectedExecutionException ex) {
            // Executor shut down: the scheduled fallback will pick the flag up.
            log.debug("summary refresh worker unavailable, leaving pending flag set", ex);
        }
    }

    /**
     * Run the refresh SQL when (and only when) a trigger is pending.
     *
     * @return true when the refresh actually executed
     */
    boolean runPendingRefresh() {
        if (!pending.compareAndSet(true, false)) return false;
        try {
            jdbc.execute(REFRESH_SQL);
            log.debug("observation read summaries refreshed");
            return true;
        } catch (RuntimeException ex) {
            // Keep the flag so the scheduled fallback retries.
            pending.set(true);
            log.warn("observation read summary refresh failed, will retry: {}", ex.getMessage());
            return false;
        }
    }

    /** Immediate synchronous refresh regardless of the pending flag (tests / ops). */
    public void refreshNow() {
        pending.set(true);
        runPendingRefresh();
    }

    boolean isPending() {
        return pending.get();
    }

    /** Fallback: drains triggers the async worker never picked up. */
    @Scheduled(fixedDelayString = "${skillhub.observation.summary-refresh-fallback-ms:600000}",
            initialDelayString = "${skillhub.observation.summary-refresh-fallback-initial-ms:30000}")
    public void scheduledFallbackRefresh() {
        runPendingRefresh();
    }
}
