package com.finesse.composite;

import com.finesse.api.engine.EngineException;
import com.finesse.composite.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 엔진 에러 코드 → 화면이 지금 받는 코드 (설계 방안 5.4절). 바꾸는 곳은 여기 한 곳뿐이라 프론트는 그대로다.
 * <pre>
 * LLM_FORMAT      502 → LLM_FORMAT_ERROR 502
 * LLM_UNAVAILABLE 503 → LLM_UNAVAILABLE  503 (+ Retry-After)
 * LLM_TIMEOUT     504 → LLM_TIMEOUT      504
 * INVALID_PAYLOAD · UNKNOWN_DOMAIN → INTERNAL_ERROR 500 (부르는 쪽 · 팩 문제라 서버 오류로 기록)
 * </pre>
 * 메시지는 백엔드 GlobalExceptionHandler와 같은 문장.
 * <p>
 * 통계: tetris-analytics의 응답은 {@link StatsRelayController}가 그대로 넘기므로 여기를 거치지 않는다.
 * tetris-analytics에 닿지 않을 때만 여기서 502 TETRIO_API_UNAVAILABLE(모놀리식과 같은 코드·문장) + Retry-After.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(EngineException.class)
    ResponseEntity<ApiErrorResponse> engine(EngineException e) {
        return switch (e.code()) {
            case LLM_FORMAT -> ResponseEntity.status(502)
                    .body(new ApiErrorResponse("LLM_FORMAT_ERROR", "AI 코멘트를 만들지 못했습니다, 잠시 후 다시 시도"));
            case LLM_TIMEOUT -> ResponseEntity.status(504)
                    .body(new ApiErrorResponse("LLM_TIMEOUT", "AI 코멘트 생성 시간이 초과되었습니다, 잠시 후 다시 시도"));
            case LLM_UNAVAILABLE -> ResponseEntity.status(503)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter(e)))
                    .body(new ApiErrorResponse("LLM_UNAVAILABLE", "일시적으로 코멘트를 생성할 수 없습니다, 잠시 후 다시 시도"));
            case INVALID_PAYLOAD, UNKNOWN_DOMAIN -> {
                log.error("엔진이 요청을 거절함 {}: {}", e.code(), e.getMessage());
                yield ResponseEntity.status(500)
                        .body(new ApiErrorResponse("INTERNAL_ERROR", "일시적인 오류가 발생했습니다, 잠시 후 다시 시도"));
            }
        };
    }

    @ExceptionHandler(StatsUnavailableException.class)
    ResponseEntity<ApiErrorResponse> statsUnavailable(StatsUnavailableException e) {
        return ResponseEntity.status(502)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(new ApiErrorResponse("TETRIO_API_UNAVAILABLE", "일시적으로 조회할 수 없습니다, 잠시 후 다시 시도"));
    }

    private static int retryAfter(EngineException e) {
        Integer seconds = EngineCallException.retryAfterOf(e);
        return seconds == null ? 5 : seconds;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrorResponse> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("BAD_REQUEST", "요청 형식이 잘못되었습니다"));
    }
}
