package com.finesse.composite;

import com.finesse.api.engine.CommentEngineService;
import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.ErrorBody;
import com.finesse.api.engine.dto.DomainInfo;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * 엔진 계약을 <b>호출하는 쪽</b> 구현 — comment-engine의 컨트롤러와 같은 인터페이스를 구현한다.
 * 그래서 finesse-api 코드는 엔진이 같은 프로세스에 있든 HTTP 너머에 있든 {@link CommentEngineService}만 본다.
 * <p>
 * 에러 응답은 다시 {@link EngineException}으로 바꾼다. 다시 시도는 LLM_UNAVAILABLE(503)일 때만, 남은 시간 안에서 한 번.
 */
@Component
public class EngineClient implements CommentEngineService {

    private static final Logger log = LoggerFactory.getLogger(EngineClient.class);

    private final RestClient rest;
    /** 에러 본문 {error_code, message}를 읽는다 */
    private final JsonMapper errorMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public EngineClient(RestClient.Builder builder, @Value("${engine.base-url}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).build();
    }

    @Override
    public LightCommentResult light(LightCommentRequest request) {
        return withOneRetry(request.deadlineMs(), remaining -> rest.post().uri("/v1/comments/light")
                .body(new LightCommentRequest(request.domain(), request.items(), remaining))
                .retrieve().onStatus(HttpStatusCode::isError, (req, resp) -> {
                    throw toEngineException(resp.getStatusCode().value(), resp.getHeaders().getFirst("Retry-After"),
                            new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8));
                })
                .body(LightCommentResult.class));
    }

    @Override
    public SectionCommentResult section(SectionCommentRequest request) {
        return withOneRetry(request.deadlineMs(), remaining -> rest.post().uri("/v1/comments/section")
                .body(new SectionCommentRequest(request.domain(), request.section(), request.data(), remaining))
                .retrieve().onStatus(HttpStatusCode::isError, (req, resp) -> {
                    throw toEngineException(resp.getStatusCode().value(), resp.getHeaders().getFirst("Retry-After"),
                            new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8));
                })
                .body(SectionCommentResult.class));
    }

    @Override
    public List<DomainInfo> domains() {
        return call(() -> rest.get().uri("/v1/domains").retrieve().body(new ParameterizedTypeReference<>() {
        }));
    }

    private interface Call<T> {
        T run(int remainingMs);
    }

    private <T> T withOneRetry(int deadlineMs, Call<T> call) {
        long start = System.nanoTime();
        try {
            return call(() -> call.run(deadlineMs));
        } catch (EngineException e) {
            if (!e.code().retryable()) {
                throw e;
            }
            Integer retryAfter = EngineCallException.retryAfterOf(e);
            int waitMs = (retryAfter == null ? 1 : retryAfter) * 1000;
            int remaining = deadlineMs - (int) ((System.nanoTime() - start) / 1_000_000) - waitMs;
            if (remaining < 1000) {
                throw e; // 기다렸다 다시 부를 시간이 없다
            }
            log.info("엔진 {} — {}ms 뒤 한 번 더 (남은 {}ms)", e.code(), waitMs, remaining);
            sleep(waitMs);
            return call(() -> call.run(remaining));
        }
    }

    private static <T> T call(Supplier<T> s) {
        try {
            return s.get();
        } catch (ResourceAccessException e) {
            // 엔진 자체에 닿지 못함 — 화면에는 지금과 같은 LLM_UNAVAILABLE로
            throw new EngineCallException(EngineErrorCode.LLM_UNAVAILABLE, "comment-engine unreachable: " + e.getMessage(), 5);
        }
    }

    private EngineException toEngineException(int status, String retryAfter, String body) {
        ErrorBody err;
        try {
            err = errorMapper.readValue(body, ErrorBody.class);
        } catch (RuntimeException parse) {
            err = new ErrorBody(null, "HTTP " + status + " " + body);
        }
        EngineErrorCode code = codeOf(err.errorCode());
        if (code == null) {
            // 모르는 에러 코드 — 일반 오류로 (5.5절)
            code = status == 503 ? EngineErrorCode.LLM_UNAVAILABLE : EngineErrorCode.LLM_FORMAT;
        }
        Integer after = null;
        if (retryAfter != null) {
            try {
                after = Integer.parseInt(retryAfter.trim());
            } catch (NumberFormatException ignored) {
                // 날짜 형식 Retry-After는 프로토타입에서 무시
            }
        }
        return new EngineCallException(code, err.message(), after);
    }

    /** 모르는 코드는 null */
    private static EngineErrorCode codeOf(String name) {
        for (EngineErrorCode c : EngineErrorCode.values()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
