import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 가짜 llm-server — legacy 방식(tetris 팩)을 진짜 LLM 없이 돌려 보기 위한 것. 지금 llm-server와 같은 경로 · 칸 이름.
 * <pre>
 * 실행: java tools\MockLlmServer.java          (9000번)
 *       java tools\MockLlmServer.java 9000 3000  (응답마다 3초 지연 — 시간 초과 시험용)
 * </pre>
 */
public class MockLlmServer {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
        long delayMs = args.length > 1 ? Long.parseLong(args[1]) : 0;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/v1/light/sentence", ex -> {
            String body = read(ex);
            String stat = field(body, "stat");
            String zone = field(body, "zone");
            respond(ex, delayMs, "{\"stat\":\"" + stat + "\",\"sentence\":\"[mock] " + stat + " 지표가 '" + zone
                    + "' 영역입니다.\",\"fallback\":false,\"version\":\"mock\"}");
        });
        server.createContext("/v1/light/summary", ex -> {
            read(ex);
            respond(ex, delayMs, "{\"light_summary\":\"[mock] 이번 전적 요약 문장입니다.\",\"fallback\":false,\"version\":\"mock\"}");
        });
        server.createContext("/v1/comment/heavy-chapter", ex -> {
            String body = read(ex);
            String id = field(body, "chapter_id");
            respond(ex, delayMs, "{\"chapter_id\":\"" + id + "\",\"footnote\":\"[mock] " + id
                    + " 섹션 문장입니다.\",\"fallback\":false}");
        });
        server.start();
        System.out.println("mock llm-server http://127.0.0.1:" + port + " (delay " + delayMs + "ms) — Ctrl+C로 종료");
    }

    private static String read(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        System.out.println(ex.getRequestURI() + " <- " + body);
        return body;
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "?";
    }

    private static void respond(HttpExchange ex, long delayMs, String json) throws IOException {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
