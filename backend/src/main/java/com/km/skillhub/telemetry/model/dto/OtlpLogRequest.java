package com.km.skillhub.telemetry.model.dto;

import java.util.List;

public class OtlpLogRequest {
    private List<ResourceLogs> resourceLogs;

    public List<ResourceLogs> getResourceLogs() { return resourceLogs; }
    public void setResourceLogs(List<ResourceLogs> value) { resourceLogs = value; }

    public static class ResourceLogs {
        private OtlpTraceRequest.Resource resource;
        private List<ScopeLogs> scopeLogs;
        public OtlpTraceRequest.Resource getResource() { return resource; }
        public void setResource(OtlpTraceRequest.Resource value) { resource = value; }
        public List<ScopeLogs> getScopeLogs() { return scopeLogs; }
        public void setScopeLogs(List<ScopeLogs> value) { scopeLogs = value; }
    }

    public static class ScopeLogs {
        private List<LogRecord> logRecords;
        public List<LogRecord> getLogRecords() { return logRecords; }
        public void setLogRecords(List<LogRecord> value) { logRecords = value; }
    }

    public static class LogRecord {
        private String timeUnixNano;
        private String observedTimeUnixNano;
        private String traceId;
        private String spanId;
        private String severityText;
        private Integer severityNumber;
        private List<OtlpTraceRequest.KeyValue> attributes;
        public String getTimeUnixNano() { return timeUnixNano; }
        public void setTimeUnixNano(String value) { timeUnixNano = value; }
        public String getObservedTimeUnixNano() { return observedTimeUnixNano; }
        public void setObservedTimeUnixNano(String value) { observedTimeUnixNano = value; }
        public String getTraceId() { return traceId; }
        public void setTraceId(String value) { traceId = value; }
        public String getSpanId() { return spanId; }
        public void setSpanId(String value) { spanId = value; }
        public String getSeverityText() { return severityText; }
        public void setSeverityText(String value) { severityText = value; }
        public Integer getSeverityNumber() { return severityNumber; }
        public void setSeverityNumber(Integer value) { severityNumber = value; }
        public List<OtlpTraceRequest.KeyValue> getAttributes() { return attributes; }
        public void setAttributes(List<OtlpTraceRequest.KeyValue> value) { attributes = value; }
    }
}
