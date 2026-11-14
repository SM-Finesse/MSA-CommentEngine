package com.finesse.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * config-repo/gateway.yml 의 경로 규칙을 그대로 읽어, 엔진 자리에 세운 가짜 서버로 요청이 넘어가는지 본다.
 * 가짜 서버는 받은 경로를 "engine:" 뒤에 붙여 돌려준다.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class GatewayRoutingTests {

    private static final HttpServer ENGINE = startFakeEngine();

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.engine-url", () -> "http://localhost:" + ENGINE.getAddress().getPort());
        registry.add("management.server.port", () -> "0");
    }

    @AfterAll
    static void stop() {
        ENGINE.stop(0);
    }

    @Test
    void domainsGoesToEngine() throws Exception {
        HttpResponse<String> response = send(get("/v1/domains"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("engine:/v1/domains");
    }

    @Test
    void lightCommentGoesToEngine() throws Exception {
        HttpResponse<String> response = send(post("/v1/comments/light"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("engine:/v1/comments/light");
    }

    @Test
    void sectionCommentGoesToEngine() throws Exception {
        HttpResponse<String> response = send(post("/v1/comments/section"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("engine:/v1/comments/section");
    }

    @Test
    void pathOutsideRulesIsNotForwarded() throws Exception {
        assertThat(send(get("/v1/unknown")).statusCode()).isEqualTo(404);
        assertThat(send(get("/actuator/health")).statusCode()).isEqualTo(404);
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(uri(path)).GET().build();
    }

    private HttpRequest post(String path) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startFakeEngine() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = ("engine:" + exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("fake engine did not start", e);
        }
    }
}
