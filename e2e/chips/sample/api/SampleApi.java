import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * 시험용 칩(sample)의 API 서비스. 칩 꽂기 시험(e2e/plug-test.sh)에서만 쓴다. 실제 칩이 아니다.
 * "자기 서비스를 가진 칩"이 본체 수정 없이 꽂히는지 보려고, tetris 칩의 finesse-api 가 하는 일을 가장 작게 흉내 낸다.
 *
 *   GET /sample/light      엔진 계약(POST /v1/comments/light)으로 본체를 불러 받은 답을 그대로 돌려준다
 *   GET /actuator/health   {"status":"UP"} — gateway 헬스 모음에 이 칩의 서비스로 나온다
 *
 * 실행: java SampleApi.java <포트> <엔진 주소>     예: java SampleApi.java 9310 http://comment-engine:7001
 */
public class SampleApi {

    private static final String REQUEST =
            "{\"domain\":\"sample\",\"items\":[{\"metric\":\"score\",\"value\":1.5,\"zone\":\"high\"}],\"deadline_ms\":35000}";

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9310;
        String engine = args.length > 1 ? args[1] : "http://localhost:7001";
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/actuator/health", ex -> send(ex, 200, "{\"status\":\"UP\"}"));

        server.createContext("/sample/light", ex -> {
            try {
                HttpResponse<String> res = client.send(
                        HttpRequest.newBuilder(URI.create(engine + "/v1/comments/light"))
                                .timeout(Duration.ofSeconds(40))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(REQUEST, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                send(ex, res.statusCode(), res.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(ex, 503, "{\"error_code\":\"ENGINE_UNREACHABLE\"}");
            } catch (IOException e) {
                send(ex, 503, "{\"error_code\":\"ENGINE_UNREACHABLE\"}");
            }
        });

        server.start();
        System.out.println("sample-api listening on :" + port + "  engine=" + engine);
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        ex.getRequestBody().readAllBytes();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
