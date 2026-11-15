package com.finesse.engine;

import org.springframework.beans.factory.annotation.Value;
import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.ErrorBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 에러 본문을 {error_code, message}로 통일. 503이면 Retry-After를 붙인다.
 * Retry-After는 연결 실패한 생성 서버를 빼 두는 시간(engine.exclude-seconds)과 같다 — 그 뒤에 다시 부르면 서버가 돌아와 있다.
 */
@RestControllerAdvice
public class EngineExceptionHandler {

    private final String retryAfterSeconds;

    public EngineExceptionHandler(@Value("${engine.exclude-seconds}") long excludeSeconds) {
        this.retryAfterSeconds = String.valueOf(excludeSeconds);
    }

    @ExceptionHandler(EngineException.class)
    public ResponseEntity<ErrorBody> engine(EngineException e) {
        ResponseEntity.BodyBuilder res = ResponseEntity.status(e.code().status());
        if (e.code().retryable()) {
            res.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
        }
        return res.body(new ErrorBody(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody(EngineErrorCode.INVALID_PAYLOAD.name(), "request body is not valid JSON for this contract"));
    }
}
