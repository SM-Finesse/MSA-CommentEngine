package com.finesse.composite;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 화면의 통계 경로 GET /api/v1/stats/{username} — finesse-api가 tetris-analytics 응답을 고치지 않고 넘기는지.
 * 가짜 tetris-analytics가 다섯 가지로 답한다: OK(200), NOT_FOUND(404), BUSY(503 + Retry-After),
 * DROP(답 없이 연결을 끊음 = 닿지 않음), SLOW(읽기 타임아웃보다 늦게 답함).
 * 시험이 빨리 끝나도록 다시 시도 간격은 0.2초, 회로가 열려 있는 시간은 1초, 읽기 타임아웃은 0.8초로 줄였다.
 * <p>
 * 다시 시도는 가짜 서버가 받은 수가 아니라 Resilience4j 지표로 센다. 답 없이 끊긴 연결에서는 JDK HttpClient가
 * GET을 스스로 한 번 더 보내기 때문에(JDK 내장 동작, CI에서 확인), 받은 수는 Resilience4j 시도 수의 2배가 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "resilience4j.retry.instances.analytics.wait-duration=200ms",
        "resilience4j.circuitbreaker.instances.analytics.wait-duration-in-open-state=1s",
        "analytics.read-timeout-ms=800"})
class StatsRelayTest {

    enum Mode { OK, NOT_FOUND, BUSY, DROP, SLOW }

    /** 모놀리식 StatsResponse 모양의 일부. 넘기는 쪽은 내용을 모르므로 바이트가 같은지만 본다 */
    static final String STATS_JSON =
            "{\"username\":\"abc\",\"cold_start\":false,\"fixed_metrics\":{\"apm\":61.25,\"pps\":2.1}}";
    static final String NOT_FOUND_JSON =
            "{\"error_code\":\"USER_NOT_FOUND\",\"message\":\"존재하지 않는 TETR.IO 유저입니다\"}";
    static final String BUSY_JSON =
            "{\"error_code\":\"SERVER_BUSY\",\"message\":\"사용자가 많습니다, 잠시 후 다시 시도\",\"retry_after_seconds\":5}";
    static final String UNAVAILABLE_JSON =
            "{\"error_code\":\"TETRIO_API_UNAVAILABLE\",\"message\":\"일시적으로 조회할 수 없습니다, 잠시 후 다시 시도\"}";

    static volatile Mode mode = Mode.OK;
    /** 이 수만큼은 mode와 상관없이 연결을 끊는다 */
    static final AtomicInteger dropFirst = new AtomicInteger();
    /** 가짜 tetris-analytics가 받은 요청 수 */
    static final AtomicInteger hits = new AtomicInteger();
    /** 가짜 tetris-analytics가 마지막으로 받은 경로와 쿼리 (인코딩된 그대로) */
    static volatile String lastRaw;
    static final HttpServer ANALYTICS;

    static {
        try {
            ANALYTICS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ANALYTICS.createContext("/api/v1/stats/", StatsRelayTest::handle);
            ANALYTICS.setExecutor(Executors.newCachedThreadPool()); // SLOW가 다른 시험의 요청을 막지 않게
            ANALYTICS.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        hits.incrementAndGet();
        URI u = ex.getRequestURI();
        lastRaw = u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        if (dropFirst.getAndUpdate(n -> Math.max(0, n - 1)) > 0 || mode == Mode.DROP) {
            ex.close(); // 답 없이 연결을 끊는다
            return;
        }
        switch (mode) {
            case NOT_FOUND -> reply(ex, 404, NOT_FOUND_JSON);
            case BUSY -> {
                ex.getResponseHeaders().add("Retry-After", "5");
                reply(ex, 503, BUSY_JSON);
            }
            case SLOW -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                reply(ex, 200, STATS_JSON);
            }
            default -> reply(ex, 200, STATS_JSON);
        }
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
            // SLOW: 부른 쪽이 이미 끊고 갔다
        }
    }

    @DynamicPropertySource
    static void analytics(DynamicPropertyRegistry r) {
        r.add("analytics.base-url", () -> "http://127.0.0.1:" + ANALYTICS.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        ANALYTICS.stop(0);
    }

    @Autowired
    Environment env;

    @Autowired
    CircuitBreakerRegistry registry;

    @Autowired
    RetryRegistry retries;

    @BeforeEach
    void reset() {
        registry.circuitBreaker(AnalyticsHttp.NAME).reset();
        mode = Mode.OK;
        dropFirst.set(0);
        hits.set(0);
        lastRaw = null;
    }

    @Test
    void 통계를_바이트까지_그대로_넘긴다() throws Exception {
        HttpResponse<String> resp = get("/api/v1/stats/abc");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo(STATS_JSON);
        assertThat(resp.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        assertThat(lastRaw).isEqualTo("/api/v1/stats/abc");
    }

    @Test
    void 경로와_쿼리를_받은_그대로_넘긴다() throws Exception {
        // %2D(-)를 풀거나 다시 인코딩하지 않는다. refresh=abc처럼 틀린 값도 판단은 tetris-analytics가 한다
        get("/api/v1/stats/a%2Db?refresh=abc");

        assertThat(lastRaw).isEqualTo("/api/v1/stats/a%2Db?refresh=abc");
    }

    @Test
    void 에러_응답도_상태_본문_Retry_After를_그대로_넘기고_다시_부르지_않는다() throws Exception {
        mode = Mode.NOT_FOUND;
        HttpResponse<String> notFound = get("/api/v1/stats/nosuchuser");
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.body()).isEqualTo(NOT_FOUND_JSON);

        mode = Mode.BUSY;
        HttpResponse<String> busy = get("/api/v1/stats/abc");
        assertThat(busy.statusCode()).isEqualTo(503);
        assertThat(busy.body()).isEqualTo(BUSY_JSON);
        assertThat(busy.headers().firstValue("Retry-After")).hasValue("5");

        assertThat(hits.get()).as("tetris-analytics를 부른 수").isEqualTo(2); // 요청마다 한 번씩
        assertThat(state()).isEqualTo(CircuitBreaker.State.CLOSED);         // 답했으니 살아 있다
    }

    @Test
    void 닿지_않으면_한_번만_다시_부른다() throws Exception {
        // 처음 한 번 끊긴 것은 JDK HttpClient가 스스로 다시 보내서 넘어가므로, 두 번 끊어야 Resilience4j 다시 시도까지 간다
        dropFirst.set(2);
        long before = retryMetrics().getNumberOfSuccessfulCallsWithRetryAttempt();

        HttpResponse<String> resp = get("/api/v1/stats/abc");

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo(STATS_JSON);
        assertThat(retryMetrics().getNumberOfSuccessfulCallsWithRetryAttempt() - before)
                .as("다시 시도해서 성공한 호출 수").isEqualTo(1);
    }

    @Test
    void 계속_닿지_않으면_모놀리식과_같은_502_TETRIO_API_UNAVAILABLE() throws Exception {
        mode = Mode.DROP;
        long before = retryMetrics().getNumberOfFailedCallsWithRetryAttempt();

        HttpResponse<String> resp = get("/api/v1/stats/abc");

        assertThat(resp.statusCode()).isEqualTo(502);
        assertThat(resp.body()).isEqualTo(UNAVAILABLE_JSON);
        assertThat(resp.headers().firstValue("Retry-After")).hasValue("5");
        assertThat(retryMetrics().getNumberOfFailedCallsWithRetryAttempt() - before)
                .as("다시 시도하고도 실패한 호출 수").isEqualTo(1);
    }

    @Test
    void 답이_늦으면_다시_부르지_않고_502() throws Exception {
        mode = Mode.SLOW;
        long before = retryMetrics().getNumberOfFailedCallsWithoutRetryAttempt();

        long start = System.nanoTime();
        HttpResponse<String> resp = get("/api/v1/stats/abc");
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(resp.statusCode()).isEqualTo(502);
        assertThat(resp.body()).isEqualTo(UNAVAILABLE_JSON);
        assertThat(hits.get()).as("tetris-analytics를 부른 수").isEqualTo(1); // 다시 부르면 화면이 그만큼 더 기다린다
        assertThat(retryMetrics().getNumberOfFailedCallsWithoutRetryAttempt() - before)
                .as("다시 시도 없이 실패한 호출 수").isEqualTo(1);
        assertThat(ms).as("걸린 시간(ms)").isLessThan(1800);                 // 읽기 타임아웃 0.8초에서 끊음
    }

    @Test
    void 계속_닿지_않으면_회로가_열리고_부르지_않는다() throws Exception {
        mode = Mode.DROP;
        for (int i = 0; i < 3; i++) {
            assertThat(get("/api/v1/stats/abc").statusCode()).isEqualTo(502); // 한 번에 2회씩 시도, 5회째 실패에서 열림
        }
        assertThat(state()).isEqualTo(CircuitBreaker.State.OPEN);

        int before = hits.get();
        long start = System.nanoTime();
        HttpResponse<String> resp = get("/api/v1/stats/abc");
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(resp.statusCode()).isEqualTo(502);
        assertThat(resp.body()).isEqualTo(UNAVAILABLE_JSON);
        assertThat(resp.headers().firstValue("Retry-After")).hasValue("1"); // 회로가 열려 있는 시간 (시험은 1초)
        assertThat(hits.get()).as("회로가 열린 뒤 부른 수").isEqualTo(before);
        assertThat(ms).as("걸린 시간(ms)").isLessThan(500);
    }

    private Retry.Metrics retryMetrics() {
        return retries.retry(AnalyticsHttp.NAME).getMetrics();
    }

    private CircuitBreaker.State state() {
        return registry.circuitBreaker(AnalyticsHttp.NAME).getState();
    }

    private HttpResponse<String> get(String pathAndQuery) throws IOException, InterruptedException {
        int port = env.getRequiredProperty("local.server.port", Integer.class);
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
