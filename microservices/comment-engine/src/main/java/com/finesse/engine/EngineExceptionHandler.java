package com.finesse.engine;

import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.ErrorBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 에러 본문을 {error_code, message}로 통일. 503이면 Retry-After를 붙인다 */
@RestControllerAdvice
public class EngineExceptionHandler {

    @ExceptionHandler(EngineException.class)
    public ResponseEntity<ErrorBody> engine(EngineException e) {
        ResponseEntity.BodyBuilder res = ResponseEntity.status(e.code().status());
        if (e.code().retryable()) {
            res.header(HttpHeaders.RETRY_AFTER, "30");
        }
        return res.body(new ErrorBody(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody(EngineErrorCode.INVALID_PAYLOAD.name(), "request body is not valid JSON for this contract"));
    }
}
