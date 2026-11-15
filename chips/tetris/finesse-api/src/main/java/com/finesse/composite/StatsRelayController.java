package com.finesse.composite;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면이 부르는 통계 경로 {@code GET /api/v1/stats/{username}} (모놀리식과 같은 경로, API 명세서 4.1절).
 * <p>
 * 계산은 tetris-analytics가 한다 (모놀리식 StatsService를 고치지 않고 옮긴 것). 여기서는 받은 경로와 쿼리를 그대로 넘기고,
 * 돌아온 상태·본문·Retry-After를 그대로 화면에 준다. 그래서 응답(400 INVALID_USERNAME, 404 USER_NOT_FOUND,
 * 502 TETRIO_API_UNAVAILABLE, 503 SERVER_BUSY 포함)이 모놀리식과 같다. 캐시도 tetris-analytics 안에 있다.
 * <p>
 * tetris-analytics에 닿지 않거나 답이 없거나 회로가 열려 있으면 502 TETRIO_API_UNAVAILABLE + Retry-After
 * ({@link ApiErrorHandler}). 모놀리식도 통계를 만들지 못한 내부 사정(실행 거부, 마감 초과)을 이 코드로 답한다.
 */
@RestController
public class StatsRelayController {

    private static final Logger log = LoggerFactory.getLogger(StatsRelayController.class);

    /** 닿지 않을 때 화면에 알려 줄 Retry-After(초) — 엔진 쪽과 같은 값 */
    static final int UNREACHABLE_RETRY_AFTER_SECONDS = 5;

    private final AnalyticsHttp analytics;
    /** 회로가 열려 있을 때 화면에 알려 줄 Retry-After(초) = 회로가 열려 있는 시간 */
    private final int circuitOpenSeconds;

    public StatsRelayController(AnalyticsHttp analytics,
                                @Value("${resilience4j.circuitbreaker.instances.analytics.wait-duration-in-open-state}")
                                Duration circuitOpen) {
        this.analytics = analytics;
        this.circuitOpenSeconds = (int) Math.max(1, circuitOpen.toSeconds());
    }

    @GetMapping("/api/v1/stats/{username}")
    public ResponseEntity<byte[]> stats(HttpServletRequest request) {
        String query = request.getQueryString();
        String raw = request.getRequestURI() + (query == null ? "" : "?" + query);

        AnalyticsHttp.Relayed r;
        try {
            r = analytics.get(raw);
        } catch (AnalyticsUnreachableException | AnalyticsTimeoutException e) {
            log.warn("통계를 넘기지 못함 {}: {}", raw, e.getMessage());
            throw new StatsUnavailableException(e.getMessage(), UNREACHABLE_RETRY_AFTER_SECONDS, e);
        } catch (CallNotPermittedException e) {
            throw new StatsUnavailableException("circuit to tetris-analytics is open", circuitOpenSeconds, e);
        }

        ResponseEntity.BodyBuilder out = ResponseEntity.status(r.status());
        if (r.contentType() != null) {
            out.contentType(r.contentType());
        }
        if (r.retryAfter() != null) {
            out.header(HttpHeaders.RETRY_AFTER, r.retryAfter());
        }
        return out.body(r.body());
    }
}
