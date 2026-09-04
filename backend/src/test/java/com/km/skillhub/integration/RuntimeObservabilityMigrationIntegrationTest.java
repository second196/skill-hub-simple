package com.km.skillhub.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RuntimeObservabilityMigrationIntegrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsAuthoritativeTablesAndBoundedUtcPartitions() {
        List<String> requiredTables = Arrays.asList(
                "telemetry_ingest_batch", "runtime_event_dedup", "runtime_event", "agent_trace",
                "skill_invocation", "metric_aggregate", "telemetry_aggregation_outbox",
                "telemetry_aggregation_checkpoint");
        for (String table : requiredTables) {
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table));
        }

        List<String> partitions = jdbcTemplate.queryForList(
                "SELECT child.relname FROM pg_inherits JOIN pg_class parent ON pg_inherits.inhparent = parent.oid "
                        + "JOIN pg_class child ON pg_inherits.inhrelid = child.oid WHERE parent.relname = 'runtime_event'",
                String.class);
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        Set<String> expectedPartitions = new HashSet<String>();
        for (int offset = 0; offset < 3; offset++) {
            expectedPartitions.add("runtime_event_" + currentMonth.plusMonths(offset).toString().replace('-', '_'));
        }
        assertTrue(partitions.containsAll(expectedPartitions));
        assertFalse(partitions.stream().anyMatch(name -> name.contains("default")));

        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename IN "
                        + "('runtime_event', 'agent_trace', 'skill_invocation')",
                String.class);
        assertTrue(indexes.contains("idx_runtime_event_scope_time"));
        assertTrue(indexes.contains("idx_runtime_event_trace_time"));
        assertTrue(indexes.contains("idx_skill_invocation_version_time"));
    }
}
