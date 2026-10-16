package com.km.skillhub.observation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Summary refresh trigger contract: async-after-ingest + scheduled fallback both
 * drain one coalesced dirty flag; failures keep the flag for a retry.
 */
class ObservationSummaryRefreshServiceTest {

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        final AtomicInteger executeCount = new AtomicInteger();
        volatile boolean failNext;

        @Override
        public void execute(String sql) {
            executeCount.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("db down");
            }
        }
    }

    /** Executor that only queues: tests decide when the async trigger "runs". */
    private static final class QueuedExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return new ArrayList<Runnable>();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    @Test
    void requestSetsPendingAndAsyncTriggerRunsRefreshOnce() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        QueuedExecutor executor = new QueuedExecutor();
        ObservationSummaryRefreshService service = new ObservationSummaryRefreshService(jdbc, executor);

        service.requestReadSummaries();
        assertTrue(service.isPending());
        assertEquals(1, executor.tasks.size());
        // Not run yet — the ingest request path never blocks on the rebuild.
        assertEquals(0, jdbc.executeCount.get());

        executor.tasks.get(0).run();
        assertFalse(service.isPending());
        assertEquals(1, jdbc.executeCount.get());
    }

    @Test
    void multipleTriggersCoalesceIntoOneRefresh() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        QueuedExecutor executor = new QueuedExecutor();
        ObservationSummaryRefreshService service = new ObservationSummaryRefreshService(jdbc, executor);

        service.requestReadSummaries();
        service.requestReadSummaries();
        service.requestReadSummaries();

        assertTrue(service.runPendingRefresh());
        assertEquals(1, jdbc.executeCount.get());
        assertFalse(service.runPendingRefresh());
        assertEquals(1, jdbc.executeCount.get());
    }

    @Test
    void failedRefreshKeepsPendingFlagForScheduledFallbackRetry() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        QueuedExecutor executor = new QueuedExecutor();
        ObservationSummaryRefreshService service = new ObservationSummaryRefreshService(jdbc, executor);

        jdbc.failNext = true;
        service.requestReadSummaries();
        assertFalse(service.runPendingRefresh());
        assertTrue(service.isPending());

        // The @Scheduled fallback drains the same flag and retries.
        service.scheduledFallbackRefresh();
        assertEquals(2, jdbc.executeCount.get());
        assertFalse(service.isPending());
    }

    @Test
    void refreshNowRunsEvenWithoutPendingFlag() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ObservationSummaryRefreshService service = new ObservationSummaryRefreshService(
                jdbc, new QueuedExecutor());

        assertFalse(service.isPending());
        service.refreshNow();
        assertEquals(1, jdbc.executeCount.get());
        assertFalse(service.isPending());
    }

    @Test
    void scheduledFallbackIsANoOpWhenNothingPending() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ObservationSummaryRefreshService service = new ObservationSummaryRefreshService(
                jdbc, new QueuedExecutor());

        service.scheduledFallbackRefresh();
        assertEquals(0, jdbc.executeCount.get());
    }

    @Test
    void refreshCallsTheV16Function() {
        assertEquals("SELECT refresh_observation_read_summaries()",
                ObservationSummaryRefreshService.REFRESH_SQL);
    }
}
