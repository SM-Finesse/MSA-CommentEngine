package com.finesse.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시험용 가짜 생성 서버. ③ 생성 서버 규약의 경로 3개를 받고, 받은 요청을 기록한다.
 * 엔진이 응답의 stat · chapter_id가 요청과 같은지 확인하므로 받은 값을 그대로 돌려준다.
 */
final class StubGenServer implements AutoCloseable {

    record Received(String path, String body) {
    }

    final List<Received> received = new CopyOnWriteArrayList<>();

    /** 0이 아니면 섹션 경로가 이 상태 코드로 답한다 (생성 서버가 에러로 답하는 상황) */
    volatile int sectionStatus = 0;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpServer server;

    StubGenServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/light/sentence", ex -> {
            JsonNode req = read(ex, "/v1/light/sentence");
            reply(ex, "{\"stat\":\"" + req.at("/stat").asString()
                    + "\",\"sentence\":\"[stub] 문장\",\"fallback\":false,\"version\":\"stub\"}");
        });
        server.createContext("/v1/light/summary", ex -> {
            read(ex, "/v1/light/summary");
            reply(ex, "{\"light_summary\":\"[stub] 요약\",\"fallback\":false,\"version\":\"stub\"}");
        });
        server.createContext("/v1/comment/heavy-chapter", ex -> {
            JsonNode req = read(ex, "/v1/comment/heavy-chapter");
            if (sectionStatus != 0) {
                ex.sendResponseHeaders(sectionStatus, -1);
                ex.close();
                return;
            }
            reply(ex, "{\"chapter_id\":\"" + req.at("/chapter_id").asString()
                    + "\",\"footnote\":\"[stub] 섹션 문장\",\"fallback\":true}");
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private JsonNode read(HttpExchange ex, String path) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(path, body));
        return mapper.readTree(body);
    }

    private static void reply(HttpExchange ex, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
