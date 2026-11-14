import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 두 번째 칩의 "작은 생성 서버" (확정안 9절 4단계).
 * ③ 생성 서버 규약 3개 경로만 구현한다. LLM 없음, 품질 낮음 — 슬롯에 꽂히는지만 보면 된다.
 * 어느 도메인 지표든 받는다 (stat 이름을 그대로 문장에 씀).
 *
 * 실행:   java small-gen/SmallGenServer.java            (기본 9200 포트)
 *         java small-gen/SmallGenServer.java 9101       (다른 포트)
 * 장애 흉내 (PowerShell):
 *         $env:FAIL_RATE=50;  java small-gen/SmallGenServer.java   요청 50%를 500으로
 *         $env:DELAY_MS=4000; java small-gen/SmallGenServer.java   매 요청 4초 지연
 */
public class SmallGenServer {

    private static final int FAIL_RATE = intEnv("FAIL_RATE", 0);
    private static final int DELAY_MS = intEnv("DELAY_MS", 0);

    private static final Pattern STAT = Pattern.compile("\"stat\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VALUE = Pattern.compile("\"value\"\\s*:\\s*(-?[0-9.eE+-]+)");
    private static final Pattern ZONE = Pattern.compile("\"zone\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern SECTION = Pattern.compile("\"chapter_id\"\\s*:\\s*\"([^\"]*)\"");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9200;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/v1/light/sentence", ex -> handle(ex, body -> {
            String stat = find(STAT, body);
            String value = find(VALUE, body);
            String zone = find(ZONE, body);
            String sentence = "[small-gen:" + port + "] " + stat + " 값은 " + value + "이고 '" + zone + "' 구간입니다.";
            return "{\"stat\":\"" + esc(stat) + "\",\"sentence\":\"" + esc(sentence) + "\",\"fallback\":false,\"version\":\"small-gen-0.1\"}";
        }));

        server.createContext("/v1/light/summary", ex -> handle(ex, body -> {
            List<String> stats = new ArrayList<>();
            Matcher m = STAT.matcher(body);
            while (m.find()) {
                stats.add(m.group(1));
            }
            String summary = "[small-gen:" + port + "] 지표 " + stats.size() + "개를 봤습니다: " + String.join(", ", stats) + ".";
            return "{\"light_summary\":\"" + esc(summary) + "\",\"fallback\":false,\"version\":\"small-gen-0.1\"}";
        }));

        server.createContext("/v1/comment/heavy-chapter", ex -> handle(ex, body -> {
            String section = find(SECTION, body);
            String text = "[small-gen:" + port + "] '" + section + "' 섹션 데이터 " + body.length() + "바이트를 받았습니다.";
            return "{\"chapter_id\":\"" + esc(section) + "\",\"footnote\":\"" + esc(text) + "\",\"fallback\":false}";
        }));

        server.start();
        System.out.println("small-gen listening on :" + port + "  FAIL_RATE=" + FAIL_RATE + "%  DELAY_MS=" + DELAY_MS);
    }

    interface Responder {
        String respond(String body);
    }

    private static void handle(HttpExchange ex, Responder responder) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        System.out.println("<- " + ex.getRequestURI().getPath() + " " + body);
        if (DELAY_MS > 0) {
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ThreadLocalRandom.current().nextInt(100) < FAIL_RATE) {
            System.out.println("-> 500 (FAIL_RATE)");
            send(ex, 500, "{\"detail\":\"simulated failure\"}");
            return;
        }
        send(ex, 200, responder.respond(body));
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int intEnv(String key, int def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : Integer.parseInt(v.strip());
    }
}
