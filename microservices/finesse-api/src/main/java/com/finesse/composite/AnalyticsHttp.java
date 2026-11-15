package com.finesse.composite;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * tetris-analytics HTTP 호출 한 번. 받은 응답(상태, 본문, 헤더 일부)을 고치지 않고 그대로 돌려준다.
 * 4xx·5xx도 예외가 아니라 응답이다. tetris-analytics가 에러로 답했다면 살아 있다는 뜻이라 회로에도 세지 않는다.
 * <ul>
 *   <li>다시 시도: 닿지 않을 때({@link AnalyticsUnreachableException})만 1초 뒤 한 번 (resilience4j.retry.instances.analytics)</li>
 *   <li>회로 차단기: 닿지 않거나 답이 없는 일이 잦으면 열려서, 열려 있는 동안 부르지 않고 바로 실패한다</li>
 * </ul>
 * 애너테이션이 걸리려면 다른 빈에서 불러야 해서 {@link StatsRelayController}와 나눠 두었다 ({@link EngineHttp}와 같음).
 * <p>
 * 참고: tetris-analytics가 답 없이 연결을 끊으면 JDK HttpClient가 GET을 스스로 한 번 더 보낸다 (JDK 내장 동작).
 * 그래서 이 경우 tetris-analytics가 받는 요청은 Resilience4j 시도 수의 2배(최대 4번)다. GET이라 문제는 없다.
 */
@Component
public class AnalyticsHttp {

    static final String NAME = "analytics";

    /** 화면에 그대로 넘기는 응답 */
    public record Relayed(int status, MediaType contentType, String retryAfter, byte[] body) {
    }

    private final RestClient rest;
    private final String baseUrl;

    public AnalyticsHttp(RestClient.Builder builder,
                         @Value("${analytics.base-url}") String baseUrl,
                         @Value("${analytics.connect-timeout-ms}") int connectTimeoutMs,
                         @Value("${analytics.read-timeout-ms}") int readTimeoutMs) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.rest = builder.requestFactory(factory).build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * GET. 경로와 쿼리는 화면에서 받은 그대로(이미 인코딩된 문자열) 붙인다 — 다시 인코딩하면 유저명이 달라질 수 있다.
     */
    @Retry(name = NAME)
    @CircuitBreaker(name = NAME)
    public Relayed get(String rawPathAndQuery) {
        URI uri = URI.create(baseUrl + rawPathAndQuery);
        try {
            return rest.get().uri(uri).exchange((request, response) -> new Relayed(
                    response.getStatusCode().value(),
                    response.getHeaders().getContentType(),
                    response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                    response.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                throw new AnalyticsTimeoutException(e);
            }
            throw new AnalyticsUnreachableException(e);
        }
    }

    /** 연결은 됐는데 답이 늦은 것만 true. 연결 타임아웃은 닿지 않은 것으로 본다 */
    static boolean isReadTimeout(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (c instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
