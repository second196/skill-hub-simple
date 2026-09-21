package com.km.skillhub.skill;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(SkillApiException.class)
    public ResponseEntity<Map<String, Object>> skillApi(SkillApiException exception) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", exception.getMessage());
        body.put("code", exception.getCode());
        Map<String, Object> details = exception.getDetails();
        if (details != null && !details.isEmpty()) {
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                body.put(entry.getKey(), entry.getValue());
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", exception.getMessage());
        body.put("code", SkillApiException.CODE_VALIDATION_ERROR);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}

