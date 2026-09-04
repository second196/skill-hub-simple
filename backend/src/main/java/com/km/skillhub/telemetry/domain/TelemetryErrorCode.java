package com.km.skillhub.telemetry.domain;

import org.springframework.http.HttpStatus;

public enum TelemetryErrorCode {
    INVALID_REQUEST_ID(HttpStatus.BAD_REQUEST, "请求标识不能为空且不能超过 128 个字符"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "遥测接口仅支持 application/json"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "遥测载荷超过服务端大小限制"),
    BATCH_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "遥测事件数量超过服务端批次限制"),
    INVALID_OTLP_STRUCTURE(HttpStatus.BAD_REQUEST, "OTLP JSON 结构无效"),
    SCOPE_REQUIRED(HttpStatus.BAD_REQUEST, "OTLP 资源缺少 skillhub.scope.id"),
    SCOPE_MISMATCH(HttpStatus.BAD_REQUEST, "同一批次不能包含多个治理范围"),
    RUNTIME_REQUIRED(HttpStatus.BAD_REQUEST, "OTLP 资源缺少 service.name"),
    RUNTIME_MISMATCH(HttpStatus.BAD_REQUEST, "同一批次不能包含多个运行时"),
    UNSUPPORTED_SCHEMA_VERSION(HttpStatus.BAD_REQUEST, "遥测事件 Schema 版本不受支持"),
    EVENT_REQUIRED_FIELD_MISSING(HttpStatus.BAD_REQUEST, "遥测事件缺少必填字段"),
    EVENT_TIME_INVALID(HttpStatus.BAD_REQUEST, "遥测事件时间无效"),
    EVENT_FIELD_INVALID(HttpStatus.BAD_REQUEST, "遥测事件字段无效"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "同一请求标识不能提交不同的遥测载荷"),
    BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "遥测接收批次不存在"),
    BEARER_TOKEN_REQUIRED(HttpStatus.UNAUTHORIZED, "遥测写入必须使用 Bearer Token");

    private final HttpStatus httpStatus;
    private final String message;

    TelemetryErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
