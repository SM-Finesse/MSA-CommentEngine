package com.finesse.composite;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * finesse-api → 엔진 구간의 다시 시도와 회로 차단기 (Resilience4j).
 * 가짜 엔진이 세 가지로 답한다: OK(200), BUSY(503 생성 서버 없음, Retry-After 30), DROP(답 없이 연결을 끊음 = 엔진에 닿지 않음).
 * 시험이 빨리 끝나도록 다시 시도 간격은 0.2초, 회로가 열려 있는 시간은 1초로 줄였다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "resilience4j.retry.instances.engine.wait-duration=200ms",
        "resilience4j.circuitbreaker.instances.engine.wait-duration-in-open-state=1s"})
class EngineResilienceTest {

    enum Mode { OK, BUSY, DROP }

    static volatile Mode mode = Mode.OK;
    /** 이 수만큼은 mode와 상관없이 연결을 끊는다 */
    static final AtomicInteger dropFirst = new AtomicInteger();
    /** 가짜 엔진이 받은 요청 수 */
    static final AtomicInteger hits = new AtomicInteger();
    static final HttpServer ENGINE;

    static {
        try {
            ENGINE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ENGINE.createContext("/v1/comments/light", EngineResilienceTest::handle);
            ENGINE.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        ex.getRequestBody().readAllBytes();
        hits.incrementAndGet();
        if (dropFirst.getAndUpdate(n -> Math.max(0, n - 1)) > 0 || mode == Mode.DROP) {
            ex.close(); // 답 없이 연결을 끊는다
            return;
        }
        if (mode == Mode.BUSY) {
            ex.getResponseHeaders().add("Retry-After", "30");
            reply(ex, 503, "{\"error_code\":\"LLM_UNAVAILABLE\",\"message\":\"all generation servers are excluded\"}");
            return;
        }
        reply(ex, 200, """
                {"summary":"요약","items":[{"metric":"metric_a","sentence":"문장","fallback":false}],
                 "fallback":false,"versions":{"engine":"0.1.0","pack":"0.0.1"}}""");
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry r) {
        r.add("engine.base-url", () -> "http://127.0.0.1:" + ENGINE.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        ENGINE.stop(0);
    }

    @Autowired
    Environment env;

    @Autowired
    CircuitBreakerRegistry registry;

    @BeforeEach
    void reset() {
        registry.circuitBreaker(EngineHttp.NAME).reset();
        mode = Mode.OK;
        dropFirst.set(0);
        hits.set(0);
    }

    @Test
    void 엔진이_503이면_기다리지_않고_바로_화면에_넘긴다() throws Exception {
        mode = Mode.BUSY;

        long start = System.nanoTime();
        HttpResponse<String> resp = post();
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(503);
        assertThat(resp.headers().firstValue("Retry-After")).hasValue("30"); // 엔진의 값 그대로
        assertThat(ms).as("걸린 시간(ms)").isLessThan(2000);              // 30초를 기다리지 않음
        assertThat(hits.get()).as("엔진을 부른 수").isEqualTo(1);           // 다시 부르지 않음
        assertThat(state()).isEqualTo(CircuitBreaker.State.CLOSED);       // 엔진은 살아 있으니 회로에 세지 않음
    }

    @Test
    void 엔진에_닿지_않으면_한_번만_다시_부른다() throws Exception {
        dropFirst.set(1);

        HttpResponse<String> resp = post();

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        assertThat(hits.get()).as("엔진을 부른 수").isEqualTo(2);
    }

    @Test
    void 엔진에_계속_닿지_않으면_회로가_열리고_엔진을_부르지_않는다() throws Exception {
        mode = Mode.DROP;
        for (int i = 0; i < 3; i++) {
            assertThat(post().statusCode()).isEqualTo(503); // 한 번에 2회씩 시도, 5회째 실패에서 회로가 열림
        }
        assertThat(state()).isEqualTo(CircuitBreaker.State.OPEN);

        int before = hits.get();
        long start = System.nanoTime();
        HttpResponse<String> resp = post();
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(resp.statusCode()).isEqualTo(503);
        assertThat(resp.headers().firstValue("Retry-After")).hasValue("1"); // 회로가 열려 있는 시간 (시험은 1초)
        assertThat(hits.get()).as("회로가 열린 뒤 엔진을 부른 수").isEqualTo(before);
        assertThat(ms).as("걸린 시간(ms)").isLessThan(500);
    }

    @Test
    void 회로가_열린_뒤_엔진이_돌아오면_다시_닫힌다() throws Exception {
        mode = Mode.DROP;
        for (int i = 0; i < 3; i++) {
            post();
        }
        assertThat(state()).isEqualTo(CircuitBreaker.State.OPEN);

        mode = Mode.OK;
        Thread.sleep(1300); // 열림 1초가 지나면 반열림
        for (int i = 0; i < 3; i++) {
            assertThat(post().statusCode()).isEqualTo(200); // 반열림에서 3번 성공하면 닫힘
        }
        assertThat(state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private CircuitBreaker.State state() {
        return registry.circuitBreaker(EngineHttp.NAME).getState();
    }

    private HttpResponse<String> post() throws IOException, InterruptedException {
        int port = env.getRequiredProperty("local.server.port", Integer.class);
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/comment-demo/light"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"domain\":\"demo\",\"highlights\":[{\"stat\":\"metric_a\",\"value\":0.31,\"zone\":\"high\"}]}",
                                StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
