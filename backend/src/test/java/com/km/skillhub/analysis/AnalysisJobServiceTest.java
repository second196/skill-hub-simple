package com.km.skillhub.analysis;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AnalysisJobServiceTest {

    @Test
    void exposesJobStatusAfterAsyncAnalysis() throws Exception {
        ObservationAnalyzer analyzer = new ObservationAnalyzer(
                null, null, null, null, null, 10, 10, 0.8d, 2, 20, 0.6d, 0.95d) {
            @Override
            public Map<String, Object> analyze(String slug, String versionLabel, boolean refresh) {
                Map<String, Object> report = new java.util.LinkedHashMap<String, Object>();
                report.put("slug", slug);
                return report;
            }
        };
        AnalysisJobService service = new AnalysisJobService(analyzer);

        String jobId = service.start("demo-skill", null);
        assertNotNull(jobId);

        Map<String, Object> status = Collections.emptyMap();
        for (int i = 0; i < 100; i++) {
            status = service.status(jobId);
            if ("done".equals(status.get("status"))) break;
            Thread.sleep(10L);
        }
        assertEquals("done", status.get("status"));
        assertEquals("demo-skill", ((Map<?, ?>) status.get("result")).get("slug"));
    }
}
