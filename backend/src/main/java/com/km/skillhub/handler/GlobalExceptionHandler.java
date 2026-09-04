package com.km.skillhub.handler;

import com.km.skillhub.asset.service.SkillPackageConflictException;
import com.km.skillhub.asset.service.SkillPackageValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SkillPackageValidationException.class)
    public ResponseEntity<ErrorResponse> handlePackageValidation(SkillPackageValidationException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(SkillPackageConflictException.class)
    public ResponseEntity<ErrorResponse> handlePackageConflict(SkillPackageConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_ARGUMENT", exception.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleCredentials(BadCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("INVALID_CREDENTIALS", "账户或密码错误"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("FORBIDDEN", "无权执行此操作"));
    }

    public static class ErrorResponse {
        private final String code;
        private final String message;
        private final OffsetDateTime occurredAt = OffsetDateTime.now();

        public ErrorResponse(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public OffsetDateTime getOccurredAt() {
            return occurredAt;
        }
    }
}
