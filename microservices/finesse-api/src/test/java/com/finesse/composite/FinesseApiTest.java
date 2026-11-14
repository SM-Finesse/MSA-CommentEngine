package com.finesse.composite;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 가짜 엔진을 세워 두고, finesse-api가 계약대로 부르고 · 응답을 백엔드 모양으로 바꾸고 · 에러 코드를 바꾸는지 본다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FinesseApiTest {

    static final List<String> ENGINE_REQUESTS = new CopyOnWriteArrayList<>();
    static final HttpServer ENGINE;

    static {
        try {
            ENGINE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ENGINE.createContext("/v1/comments/light", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ENGINE_REQUESTS.add(body);
                int status = 200;
                String resp = """
                        {"summary":"요약","items":[{"metric":"delta_app","sentence":"문장","fallback":false}],
                         "fallback":false,"versions":{"engine":"0.1.0","pack":"2026.11"}}""";
                if (body.contains("\"broken_format\"")) {
                    status = 502;
                    resp = "{\"error_code\":\"LLM_FORMAT\",\"message\":\"x\"}";
                } else if (body.contains("\"unknown_pack\"")) {
                    status = 404;
                    resp = "{\"error_code\":\"UNKNOWN_DOMAIN\",\"message\":\"domain 'unknown_pack' is not loaded\"}";
                }
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            });
            ENGINE.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
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

    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void clear() {
        ENGINE_REQUESTS.clear();
    }

    @Test
    void 엔진_계약으로_부르고_백엔드_응답_모양으로_돌려준다() throws Exception {
        HttpResponse<String> resp = post("""
                {"domain":"tetris","highlights":[{"stat":"delta_app","value":0.05,"zone":"high"},
                 {"stat":"delta_plonk","value":0.01,"zone":"none"}]}""");
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.at("/light_summary").asString()).isEqualTo("요약");
        assertThat(body.at("/highlights/0/stat").asString()).isEqualTo("delta_app");
        assertThat(body.at("/highlights/0/sentence").asString()).isEqualTo("문장");

        JsonNode sent = mapper.readTree(ENGINE_REQUESTS.getFirst());
        assertThat(sent.at("/items")).hasSize(1);                       // zone none은 보내지 않음
        assertThat(sent.at("/items/0/metric").asString()).isEqualTo("delta_app");
        assertThat(sent.at("/items/0/zone").asString()).isEqualTo("high");
        assertThat(sent.at("/deadline_ms").asInt()).isBetween(39000, 40000); // 40초 마감 중 남은 시간
    }

    @Test
    void 지표가_없으면_엔진을_부르지_않는다() throws Exception {
        HttpResponse<String> resp = post("""
                {"domain":"tetris","highlights":[{"stat":"delta_plonk","value":0.01,"zone":"none"}]}""");
        assertThat(mapper.readTree(resp.body()).at("/light_summary").asString())
                .isEqualTo(LightCommentDemoController.NO_HIGHLIGHT_SUMMARY);
        assertThat(ENGINE_REQUESTS).isEmpty();
    }

    @Test
    void 엔진_에러_코드는_지금_화면_코드로_바뀐다() throws Exception {
        HttpResponse<String> format = post("""
                {"domain":"broken_format","highlights":[{"stat":"m","value":1,"zone":"high"}]}""");
        assertThat(format.statusCode()).isEqualTo(502);
        assertThat(mapper.readTree(format.body()).at("/error_code").asString()).isEqualTo("LLM_FORMAT_ERROR");

        HttpResponse<String> unknown = post("""
                {"domain":"unknown_pack","highlights":[{"stat":"m","value":1,"zone":"high"}]}""");
        assertThat(unknown.statusCode()).isEqualTo(500);
        assertThat(mapper.readTree(unknown.body()).at("/error_code").asString()).isEqualTo("INTERNAL_ERROR");
    }

    private HttpResponse<String> post(String json) throws IOException, InterruptedException {
        int port = env.getRequiredProperty("local.server.port", Integer.class);
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/comment-demo/light"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
