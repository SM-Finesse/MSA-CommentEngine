package com.finesse.composite;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 화면의 light 코멘트 GET /api/v1/comment/{username}?scope=light — 모놀리식 CommentService.getLight 흐름.
 * 가짜 tetris-analytics(유저마다 정해 둔 stats)와 가짜 엔진(받은 지표마다 "S:지표" 문장)을 세운다.
 * 시험마다 유저명을 달리 써서 캐시가 서로 섞이지 않게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LightCommentFlowTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** 유저 → {상태, stats 본문} */
    static final Map<String, Object[]> STATS = new ConcurrentHashMap<>();
    /** 유저 → 엔진이 대체 문장을 섞었다고 답할지 */
    static final Map<String, Boolean> ENGINE_FALLBACK = new ConcurrentHashMap<>();
    static final AtomicInteger engineHits = new AtomicInteger();
    static volatile JsonNode lastEngineRequest;

    static final HttpServer ANALYTICS;
    static final HttpServer ENGINE;

    static {
        try {
            ANALYTICS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ANALYTICS.createContext("/api/v1/stats/", LightCommentFlowTest::stats);
            ANALYTICS.setExecutor(Executors.newCachedThreadPool());
            ANALYTICS.start();
            ENGINE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ENGINE.createContext("/v1/comments/light", LightCommentFlowTest::engine);
            ENGINE.setExecutor(Executors.newCachedThreadPool());
            ENGINE.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void stats(HttpExchange ex) throws IOException {
        String user = ex.getRequestURI().getRawPath().substring("/api/v1/stats/".length());
        Object[] r = STATS.getOrDefault(user, new Object[]{404,
                "{\"error_code\":\"USER_NOT_FOUND\",\"message\":\"존재하지 않는 TETR.IO 유저입니다\"}"});
        reply(ex, (Integer) r[0], (String) r[1]);
    }

    private static void engine(HttpExchange ex) throws IOException {
        engineHits.incrementAndGet();
        JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
        lastEngineRequest = req;
        boolean fallback = ENGINE_FALLBACK.getOrDefault(req.path("items").get(0).path("metric").asString(), false);
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < req.path("items").size(); i++) {
            String m = req.path("items").get(i).path("metric").asString();
            items.append(i == 0 ? "" : ",").append("{\"metric\":\"").append(m)
                    .append("\",\"sentence\":\"S:").append(m).append("\",\"fallback\":").append(fallback).append("}");
        }
        reply(ex, 200, "{\"summary\":\"요약\",\"items\":[" + items + "],\"fallback\":" + fallback
                + ",\"versions\":{\"engine\":\"0.1.0\",\"pack\":\"2026.11.15\"}}");
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
    static void fakes(DynamicPropertyRegistry r) {
        r.add("analytics.base-url", () -> "http://127.0.0.1:" + ANALYTICS.getAddress().getPort());
        r.add("engine.base-url", () -> "http://127.0.0.1:" + ENGINE.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        ANALYTICS.stop(0);
        ENGINE.stop(0);
    }

    @Autowired
    Environment env;

    /** 모놀리식 StatsResponse 모양의 일부 (light가 쓰는 칸만) */
    static String statsJson(String user, boolean coldStart, String updatedAt, String picks) {
        return """
                {"username":"%s","cold_start":%s,"match_count":120,"updated_at":"%s",
                 "delta_metrics":{"playstyle_relative":{"delta_opener":0.01},
                                  "attack":{"delta_app":0.05,"delta_weighted_app":0.02},
                                  "defense":{"delta_vs_apm":-0.1,"delta_cheese_index":3.2},
                                  "strength_split":-0.12,"delta_comeback":-0.0833,"session_vs_slope":0.004},
                 "highlight_judgement":{"ranges_version":"v1","range_group":"A","metrics":[],"picks":[%s]}}"""
                .formatted(user, coldStart, updatedAt, picks);
    }

    static String pick(String key, String zone, double judged) {
        return "{\"key\":\"%s\",\"group\":\"g\",\"value\":%s,\"q1\":0.0,\"q3\":0.1,\"zone\":\"%s\"}".formatted(key, judged, zone);
    }

    static final String THREE = String.join(",", pick("delta_app", "high", 0.05),
            pick("delta_comeback", "low", -0.02), pick("session_vs_slope", "normal", 0.004));

    @Test
    void 콜드스타트면_엔진을_부르지_않고_모놀리식과_같은_문장() throws Exception {
        STATS.put("cold", new Object[]{200, statsJson("cold", true, "2026-11-15T05:00:00Z", "")});
        int before = engineHits.get();

        HttpResponse<String> resp = get("/api/v1/comment/cold?scope=light");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(resp.body())).isEqualTo(JSON.readTree(
                "{\"light_summary\":\"최근 매치 데이터가 부족해 하이라이트를 표시할 수 없습니다.\",\"highlights\":[]}"));
        assertThat(engineHits.get()).isEqualTo(before);
    }

    @Test
    void calc가_고른_지표를_엔진_계약으로_부르고_화면_모양으로_돌려준다() throws Exception {
        STATS.put("alice", new Object[]{200, statsJson("alice", false, "2026-11-15T05:00:00Z", THREE)});

        HttpResponse<String> resp = get("/api/v1/comment/alice?scope=light");

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode req = lastEngineRequest;
        assertThat(req.path("domain").asString()).isEqualTo("tetris");
        assertThat(req.path("items").size()).isEqualTo(3);
        assertThat(req.path("items").get(0).path("metric").asString()).isEqualTo("delta_app");
        assertThat(req.path("items").get(0).path("zone").asString()).isEqualTo("high");
        assertThat(req.path("items").get(0).path("value").asDouble()).isEqualTo(0.05);
        // 판정 값(-0.02)이 아니라 화면에 보이는 원값(delta_metrics.delta_comeback)
        assertThat(req.path("items").get(1).path("value").asDouble()).isEqualTo(-0.0833);
        assertThat(req.path("items").get(1).path("zone").asString()).isEqualTo("low");
        assertThat(req.path("items").get(2).path("metric").asString()).isEqualTo("session_vs_slope");
        assertThat(req.path("deadline_ms").asInt()).isBetween(1000, 40000);

        JsonNode body = JSON.readTree(resp.body());
        assertThat(body.path("light_summary").asString()).isEqualTo("요약");
        assertThat(body.path("highlights").size()).isEqualTo(3);
        assertThat(body.path("highlights").get(0).path("stat").asString()).isEqualTo("delta_app");
        assertThat(body.path("highlights").get(0).path("sentence").asString()).isEqualTo("S:delta_app");
        assertThat(body.has("attempt_count")).as("엔진은 재요청 횟수를 알려 주지 않는다").isFalse();
    }

    @Test
    void 같은_stats면_캐시에서_꺼내고_stats가_새로_계산되면_다시_만든다() throws Exception {
        STATS.put("bob", new Object[]{200, statsJson("bob", false, "2026-11-15T05:00:00Z", THREE)});
        int before = engineHits.get();

        String first = get("/api/v1/comment/bob?scope=light").body();
        String second = get("/api/v1/comment/bob?scope=light").body();
        assertThat(second).isEqualTo(first);
        assertThat(engineHits.get() - before).as("엔진을 부른 수").isEqualTo(1);

        STATS.put("bob", new Object[]{200, statsJson("bob", false, "2026-11-15T05:10:00Z", THREE)}); // stats 다시 계산됨
        get("/api/v1/comment/bob?scope=light");
        assertThat(engineHits.get() - before).as("엔진을 부른 수").isEqualTo(2);
    }

    @Test
    void 대체_문장이_섞이면_캐시하지_않는다() throws Exception {
        String picks = String.join(",", pick("delta_weighted_app", "high", 0.02),
                pick("delta_vs_apm", "low", -0.1), pick("strength_split", "low", -0.12));
        STATS.put("carol", new Object[]{200, statsJson("carol", false, "2026-11-15T05:00:00Z", picks)});
        ENGINE_FALLBACK.put("delta_weighted_app", true);
        int before = engineHits.get();

        assertThat(get("/api/v1/comment/carol?scope=light").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/comment/carol?scope=light").statusCode()).isEqualTo(200);

        assertThat(engineHits.get() - before).as("엔진을 부른 수").isEqualTo(2);
    }

    @Test
    void 지표_고르기는_모놀리식과_같은_규칙() throws Exception {
        // none·값 없음·같은 지표는 빼고, 남은 것 중 앞에서 3개
        String picks = String.join(",", pick("delta_opener", "none", 0.01), pick("delta_plonk", "high", 0.3),
                pick("delta_app", "high", 0.05), pick("delta_app", "high", 0.05), pick("delta_vs_apm", "low", -0.1),
                pick("delta_cheese_index", "normal", 3.2), pick("strength_split", "low", -0.12));
        STATS.put("dave", new Object[]{200, statsJson("dave", false, "2026-11-15T05:00:00Z", picks)});

        get("/api/v1/comment/dave?scope=light");

        JsonNode items = lastEngineRequest.path("items");
        assertThat(items.size()).isEqualTo(3);
        assertThat(items.get(0).path("metric").asString()).isEqualTo("delta_app");
        assertThat(items.get(1).path("metric").asString()).isEqualTo("delta_vs_apm");
        assertThat(items.get(2).path("metric").asString()).isEqualTo("delta_cheese_index");
    }

    @Test
    void 하이라이트가_3개_미만이면_보여_주되_캐시하지_않는다() throws Exception {
        STATS.put("erin", new Object[]{200, statsJson("erin", false, "2026-11-15T05:00:00Z", pick("delta_app", "high", 0.05))});
        int before = engineHits.get();

        JsonNode body = JSON.readTree(get("/api/v1/comment/erin?scope=light").body());
        get("/api/v1/comment/erin?scope=light");

        assertThat(body.path("highlights").size()).isEqualTo(1);
        assertThat(engineHits.get() - before).as("엔진을 부른 수").isEqualTo(2);
    }

    @Test
    void 고른_지표가_없으면_엔진을_부르지_않는다() throws Exception {
        STATS.put("frank", new Object[]{200, statsJson("frank", false, "2026-11-15T05:00:00Z", pick("delta_opener", "none", 0.0))});
        int before = engineHits.get();

        JsonNode body = JSON.readTree(get("/api/v1/comment/frank?scope=light").body());

        assertThat(body.path("light_summary").asString()).isEqualTo("이번 전적에서는 AI 코멘트로 짚을 지표를 고르지 못했습니다.");
        assertThat(body.path("highlights").size()).isZero();
        assertThat(engineHits.get()).isEqualTo(before);
    }

    @Test
    void stats_에러는_stats_경로와_같은_응답_그대로() throws Exception {
        int before = engineHits.get();

        HttpResponse<String> resp = get("/api/v1/comment/nosuchuser?scope=light");

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(resp.body()).isEqualTo("{\"error_code\":\"USER_NOT_FOUND\",\"message\":\"존재하지 않는 TETR.IO 유저입니다\"}");
        assertThat(engineHits.get()).isEqualTo(before);
    }

    @Test
    void scope_검사는_모놀리식과_같은_문장() throws Exception {
        HttpResponse<String> wrong = get("/api/v1/comment/alice?scope=nope");
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(wrong.body())).isEqualTo(JSON.readTree(
                "{\"error_code\":\"BAD_REQUEST\",\"message\":\"scope는 light 또는 heavy만 허용됩니다: nope\"}"));

        HttpResponse<String> missing = get("/api/v1/comment/alice");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(missing.body())).isEqualTo(JSON.readTree(
                "{\"error_code\":\"BAD_REQUEST\",\"message\":\"필수 파라미터가 없습니다: scope\"}"));

        assertThat(get("/api/v1/comment/alice?scope=heavy").statusCode()).as("heavy는 E단계").isEqualTo(501);
    }

    private HttpResponse<String> get(String pathAndQuery) throws IOException, InterruptedException {
        int port = env.getRequiredProperty("local.server.port", Integer.class);
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
