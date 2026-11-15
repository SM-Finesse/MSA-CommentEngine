package com.finesse.composite;

import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.ErrorBody;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 엔진 HTTP 호출 한 번. 다시 시도와 회로 차단기는 수업 13장처럼 애너테이션 + yml(resilience4j.*.instances.engine)로 붙인다.
 * <ul>
 *   <li>다시 시도: 엔진 자체에 닿지 않을 때({@link EngineUnreachableException})만 1초 뒤 한 번</li>
 *   <li>회로 차단기: 엔진에 닿지 않는 일이 잦으면 열려서, 10초 동안 엔진을 부르지 않고 바로 실패한다</li>
 *   <li>엔진이 에러로 답한 것(503 생성 서버 없음 등)은 다시 부르지 않고 회로에도 세지 않는다</li>
 * </ul>
 * 시간 제한은 수업의 {@code @TimeLimiter} 대신 호출 타임아웃(engine.connect-timeout-ms, engine.read-timeout-ms)이다.
 * {@code @TimeLimiter}는 비동기 반환형에만 붙는데 finesse-api는 WebMVC(동기)다.
 * <p>
 * 애너테이션이 걸리려면 다른 빈에서 불러야 해서 {@link EngineClient}와 나눠 두었다.
 */
@Component
public class EngineHttp {

    static final String NAME = "engine";

    /** 남은 시간이 이보다 적으면 엔진을 부르지 않는다 */
    static final int MIN_REMAINING_MS = 1000;

    private final RestClient rest;
    /** 에러 본문 {error_code, message}를 읽는다 */
    private final JsonMapper errorMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public EngineHttp(RestClient.Builder builder,
                      @Value("${engine.base-url}") String baseUrl,
                      @Value("${engine.connect-timeout-ms}") int connectTimeoutMs,
                      @Value("${engine.read-timeout-ms}") int readTimeoutMs) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.rest = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * POST. 몸통은 부를 때마다 남은 시간(ms)으로 다시 만든다 — 다시 시도해도 처음 정한 마감은 그대로다.
     */
    @Retry(name = NAME)
    @CircuitBreaker(name = NAME)
    public <T> T post(String path, Instant deadline, IntFunction<?> body, Class<T> type) {
        int remaining = (int) Duration.between(Instant.now(), deadline).toMillis();
        if (remaining < MIN_REMAINING_MS) {
            throw new EngineCallException(EngineErrorCode.LLM_TIMEOUT,
                    "no time left to call comment-engine (" + remaining + "ms)", null);
        }
        return send(() -> rest.post().uri(path).body(body.apply(remaining)).retrieve()
                .onStatus(HttpStatusCode::isError, this::throwEngineError)
                .body(type));
    }

    @Retry(name = NAME)
    @CircuitBreaker(name = NAME)
    public <T> T get(String path, ParameterizedTypeReference<T> type) {
        return send(() -> rest.get().uri(path).retrieve()
                .onStatus(HttpStatusCode::isError, this::throwEngineError)
                .body(type));
    }

    private static <T> T send(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw new EngineUnreachableException(e);
        }
    }

    private void throwEngineError(HttpRequest request, ClientHttpResponse response) throws IOException {
        throw toEngineException(response.getStatusCode().value(), response.getHeaders().getFirst("Retry-After"),
                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
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
}
