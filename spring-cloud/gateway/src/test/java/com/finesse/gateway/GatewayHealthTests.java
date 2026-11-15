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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * gateway 헬스에 하위 서비스의 상태가 들어가는지.
 * 가짜 엔진은 UP, 시험용 칩의 서비스(fake-api)는 회로가 열린 것처럼 200 + CIRCUIT_OPEN, 세 번째 주소는 아무도 없는 포트.
 * fake-api의 헬스 주소는 본체 설정이 아니라 칩 파일(src/test/chips/fake/gateway.yml)에서 온다 —
 * 본체의 app.health 와 칩의 app.health 가 합쳐지는지도 같이 본다.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "CHIPS_DIR=src/test/chips")
class GatewayHealthTests {

    private static final HttpServer ENGINE = fake(200, "{\"status\":\"UP\"}");
    private static final HttpServer FAKE_API = fake(200, "{\"status\":\"CIRCUIT_OPEN\"}");

    @Value("${local.management.port}")
    private int managementPort;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> "0");
        registry.add("app.health.comment-engine", () -> url(ENGINE));
        registry.add("app.fake-api-health", () -> url(FAKE_API));
        registry.add("app.health.nobody", () -> "http://127.0.0.1:1/actuator/health");
    }

    @AfterAll
    static void stop() {
        ENGINE.stop(0);
        FAKE_API.stop(0);
    }

    @Test
    void 하위_서비스_상태가_gateway_헬스에_나온다() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = JsonMapper.builder().build().readTree(resp.body());

        JsonNode services = body.at("/components/services/components");
        assertThat(services.at("/comment-engine/status").asString()).as(resp.body()).isEqualTo("UP");
        assertThat(services.at("/fake-api/status").asString()).isEqualTo("DOWN");
        assertThat(services.at("/fake-api/details/status").asString()).isEqualTo("CIRCUIT_OPEN");
        assertThat(services.at("/nobody/status").asString()).isEqualTo("DOWN");
        assertThat(body.at("/status").asString()).isEqualTo("DOWN");   // 하나라도 DOWN이면 gateway 헬스도 DOWN
        assertThat(resp.statusCode()).isEqualTo(503);
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health";
    }

    private static HttpServer fake(int status, String json) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/actuator/health", ex -> {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                try (var out = ex.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
