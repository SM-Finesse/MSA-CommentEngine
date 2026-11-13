package com.finesse.engine;

import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.dto.EngineError;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** 엔진 에러 → { "error_code", "message" } (설계 방안 5.4절) */
@RestControllerAdvice
public class EngineErrorHandler {

    @ExceptionHandler(EngineException.class)
    ResponseEntity<EngineError> engine(EngineException e) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.code().httpStatus()).contentType(MediaType.APPLICATION_JSON);
        if (e.retryAfterSeconds() != null) {
            b.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return b.body(new EngineError(e.code().name(), e.getMessage()));
    }

    /** @Valid 실패 — 칸 이름은 JSON과 같게 snake_case로 알려 준다 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<EngineError> invalid(MethodArgumentNotValidException e) {
        FieldError f = e.getBindingResult().getFieldError();
        String msg = f == null ? "invalid request" : snake(f.getField()) + ": " + f.getDefaultMessage();
        return invalidPayload(msg);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<EngineError> invalid(HandlerMethodValidationException e) {
        return invalidPayload("invalid request: " + e.getMessage());
    }

    /** JSON이 아니거나 zone: "none"처럼 계약에 없는 값 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<EngineError> unreadable(HttpMessageNotReadableException e) {
        return invalidPayload("request body is not valid for the contract (bad JSON or value)");
    }

    private static ResponseEntity<EngineError> invalidPayload(String message) {
        return ResponseEntity.status(EngineErrorCode.INVALID_PAYLOAD.httpStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(new EngineError(EngineErrorCode.INVALID_PAYLOAD.name(), message));
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
