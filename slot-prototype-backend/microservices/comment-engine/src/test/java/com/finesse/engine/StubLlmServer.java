package com.finesse.engine;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 테스트용 가짜 llm-server — 받은 요청 본문을 기록하고 정해 둔 응답을 돌려준다 */
final class StubLlmServer implements AutoCloseable {

    record Received(String path, String body) {
    }

    final List<Received> received = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    StubLlmServer(Map<String, String> responses) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        responses.forEach((path, body) -> server.createContext(path, ex -> {
            received.add(new Received(path, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }));
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
