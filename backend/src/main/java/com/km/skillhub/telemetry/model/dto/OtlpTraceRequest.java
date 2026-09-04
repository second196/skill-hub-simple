package com.km.skillhub.telemetry.model.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public class OtlpTraceRequest {
    private List<ResourceSpans> resourceSpans;

    public List<ResourceSpans> getResourceSpans() { return resourceSpans; }
    public void setResourceSpans(List<ResourceSpans> value) { resourceSpans = value; }

    public static class ResourceSpans {
        private Resource resource;
        private List<ScopeSpans> scopeSpans;
        public Resource getResource() { return resource; }
        public void setResource(Resource value) { resource = value; }
        public List<ScopeSpans> getScopeSpans() { return scopeSpans; }
        public void setScopeSpans(List<ScopeSpans> value) { scopeSpans = value; }
    }

    public static class Resource {
        private List<KeyValue> attributes;
        public List<KeyValue> getAttributes() { return attributes; }
        public void setAttributes(List<KeyValue> value) { attributes = value; }
    }

    public static class ScopeSpans {
        private List<Span> spans;
        public List<Span> getSpans() { return spans; }
        public void setSpans(List<Span> value) { spans = value; }
    }

    public static class Span {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String name;
        private String startTimeUnixNano;
        private String endTimeUnixNano;
        private List<KeyValue> attributes;
        private Status status;
        public String getTraceId() { return traceId; }
        public void setTraceId(String value) { traceId = value; }
        public String getSpanId() { return spanId; }
        public void setSpanId(String value) { spanId = value; }
        public String getParentSpanId() { return parentSpanId; }
        public void setParentSpanId(String value) { parentSpanId = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getStartTimeUnixNano() { return startTimeUnixNano; }
        public void setStartTimeUnixNano(String value) { startTimeUnixNano = value; }
        public String getEndTimeUnixNano() { return endTimeUnixNano; }
        public void setEndTimeUnixNano(String value) { endTimeUnixNano = value; }
        public List<KeyValue> getAttributes() { return attributes; }
        public void setAttributes(List<KeyValue> value) { attributes = value; }
        public Status getStatus() { return status; }
        public void setStatus(Status value) { status = value; }
    }

    public static class KeyValue {
        private String key;
        private JsonNode value;
        public String getKey() { return key; }
        public void setKey(String item) { key = item; }
        public JsonNode getValue() { return value; }
        public void setValue(JsonNode item) { value = item; }
    }

    public static class Status {
        private Integer code;
        private String message;
        public Integer getCode() { return code; }
        public void setCode(Integer value) { code = value; }
        public String getMessage() { return message; }
        public void setMessage(String value) { message = value; }
    }
}
