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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

/**
 * 화면의 heavy 코멘트 GET /api/v1/comment/{username}?scope=heavy (SSE) — 모놀리식 CommentService.getHeavyStream 흐름.
 * 가짜 tetris-analytics(유저마다 정해 둔 stats)와 가짜 엔진(섹션마다 "T:섹션" 문장, 섹션별로 실패 · 지연을 정할 수 있음)을 세운다.
 * 시험마다 유저명을 달리 써서 캐시가 서로 섞이지 않게 한다. 전체 마감은 시험용으로 3초.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.endpoint.heavy-seconds=3", "app.heavy.parallel-sections=2"})
class HeavyCommentFlowTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> CHAPTERS = List.of("tr_trend", "playstyle", "attack", "defense",
            "strength_split", "comeback_rate", "session_vs_slope", "rivals");

    /** 유저 → {상태, stats 본문} */
    static final Map<String, Object[]> STATS = new ConcurrentHashMap<>();
    /** 섹션 → 엔진의 행동: "503" · "504" · "502" (에러), "fallback" (생성 서버 대체 문장), "slow" (6초 뒤 답) */
    static final Map<String, String> BEHAVIOR = new ConcurrentHashMap<>();
    /** 엔진이 받은 section 요청 */
    static final List<JsonNode> ENGINE_REQUESTS = new CopyOnWriteArrayList<>();

    static final HttpServer ANALYTICS;
    static final HttpServer ENGINE;

    static {
        try {
            ANALYTICS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ANALYTICS.createContext("/api/v1/stats/", HeavyCommentFlowTest::stats);
            ANALYTICS.setExecutor(Executors.newCachedThreadPool());
            ANALYTICS.start();
            ENGINE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ENGINE.createContext("/v1/comments/section", HeavyCommentFlowTest::engine);
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
        JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
        ENGINE_REQUESTS.add(req);
        String section = req.path("section").asString();
        String behavior = BEHAVIOR.getOrDefault(section, "ok");
        switch (behavior) {
            case "503" -> {
                ex.getResponseHeaders().add("Retry-After", "30");
                reply(ex, 503, "{\"error_code\":\"LLM_UNAVAILABLE\",\"message\":\"no generation server reachable\"}");
            }
            case "504" -> reply(ex, 504, "{\"error_code\":\"LLM_TIMEOUT\",\"message\":\"deadline exceeded\"}");
            case "502" -> reply(ex, 502, "{\"error_code\":\"LLM_FORMAT\",\"message\":\"bad format\"}");
            default -> {
                if (behavior.equals("slow")) {
                    try {
                        Thread.sleep(6000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                reply(ex, 200, "{\"section\":\"" + section + "\",\"text\":\"T:" + section + "\",\"fallback\":"
                        + behavior.equals("fallback") + ",\"versions\":{\"engine\":\"0.1.0\",\"pack\":\"2026.11.15\"}}");
            }
        }
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

    @BeforeEach
    void reset() {
        BEHAVIOR.clear();
        ENGINE_REQUESTS.clear();
    }

    /** 모놀리식 StatsResponse 모양의 일부 (heavy가 쓰는 칸). strength_split은 일부러 없다 */
    static String statsJson(String user, boolean coldStart, int rivals) {
        String items = IntStream.range(0, rivals)
                .mapToObj(i -> "{\"nickname_masked\":\"pla***_%d\",\"matches\":%d,\"wins\":10,\"losses\":%d,\"last_match_at\":\"2026-11-01T00:00:00Z\"}"
                        .formatted(i, 20 - i, 10 - i))
                .collect(Collectors.joining(","));
        return """
                {"username":"%s","cold_start":%s,"match_count":120,"updated_at":"2026-11-16T00:00:00Z",
                 "delta_metrics":{"tr_trend_delta":12.5,
                                  "tr_trend_basis":{"recent_matches":30,"total_matches":120,"recent_avg_tr":1.0,"overall_avg_tr":2.0},
                                  "playstyle_relative":{"delta_opener":0.1,"delta_plonk":-0.22,"delta_inf_ds":-0.17},
                                  "attack":{"delta_app":-0.05,"delta_weighted_app":-1.34,
                                            "my_avg":{"app":0.7,"weighted_app":1.1},"opp_avg":{"app":0.75,"weighted_app":2.44}},
                                  "defense":{"delta_vs_apm":-0.07,"delta_cheese_index":-2.56,
                                             "my_avg":{"vs_apm":2.1,"cheese_index":30.0},"opp_avg":{"vs_apm":2.17,"cheese_index":32.56}},
                                  "comeback_rate":0.31,"comeback_rate_against":0.29,"delta_comeback":0.02,
                                  "comeback_samples":{"comeback_opportunities":41,"comeback_won":13,
                                                      "comeback_against_opportunities":24,"comeback_against_allowed":7},
                                  "session_vs_slope":0.55},
                 "rivals":{"items":[%s],"page":1,"page_size":20,"total":%d}}"""
                .formatted(user, coldStart, items, rivals);
    }

    @Test
    void 챕터_8개를_보내고_done으로_끝난다() throws Exception {
        STATS.put("hv-ok", new Object[]{200, statsJson("hv-ok", false, 7)});

        HttpResponse<String> resp = get("/api/v1/comment/hv-ok?scope=heavy");
        List<Event> events = parse(resp.body());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(resp.headers().firstValue("X-Accel-Buffering")).contains("no");
        assertThat(events.getFirst().name()).isEqualTo("start");
        assertThat(events.getFirst().data().path("total_chapters").asInt()).isEqualTo(8);
        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(events.getLast().data().path("completed").asInt()).isEqualTo(8);
        assertThat(events.getLast().data().path("failed_chapters").isEmpty()).isTrue();
        assertThat(events.getLast().data().path("meta").has("elapsed_ms")).isTrue();

        List<Event> chapters = events.stream().filter(e -> e.name().equals("chapter")).toList();
        assertThat(chapters.stream().map(Event::id).toList()).containsExactlyInAnyOrderElementsOf(CHAPTERS);
        for (Event c : chapters) {
            // 모놀리식과 같은 모양: chapter_id, status, footnote. 엔진을 거친 챕터에는 attempt_count가 없다 (알려진 차이)
            assertThat(c.data().path("chapter_id").asString()).isEqualTo(c.id());
            assertThat(c.data().path("status").asString()).isEqualTo("ok");
            assertThat(c.data().path("footnote").asString()).isEqualTo("T:" + c.id());
            assertThat(Set.copyOf(c.data().propertyNames())).containsExactlyInAnyOrder("chapter_id", "status", "footnote");
        }
    }

    @Test
    void 엔진에는_tetris_칩의_섹션과_모놀리식과_같은_data를_넘긴다() throws Exception {
        STATS.put("hv-data", new Object[]{200, statsJson("hv-data", false, 7)});

        get("/api/v1/comment/hv-data?scope=heavy");

        assertThat(ENGINE_REQUESTS).hasSize(8);
        for (JsonNode req : ENGINE_REQUESTS) {
            assertThat(req.path("domain").asString()).isEqualTo("tetris");
            assertThat(req.path("data").isObject()).as(req.path("section").asString()).isTrue();
            assertThat(req.path("deadline_ms").asInt()).isBetween(1000, 3000);   // 전체 마감(시험은 3초)에서 남은 시간
        }
        assertThat(data("tr_trend").path("tr_trend_delta").asDouble()).isEqualTo(12.5);
        assertThat(Set.copyOf(data("tr_trend").propertyNames())).containsExactly("tr_trend_delta");   // basis는 넣지 않는다
        assertThat(Set.copyOf(data("playstyle").propertyNames()))
                .containsExactlyInAnyOrder("delta_opener", "delta_plonk", "delta_inf_ds");            // 값이 없는 delta_stride는 키째 없음
        // my_avg · opp_avg는 화면 막대용 — 생성 서버는 모르는 키를 거절하므로 Δ 두 개만
        assertThat(Set.copyOf(data("attack").propertyNames())).containsExactlyInAnyOrder("delta_app", "delta_weighted_app");
        assertThat(Set.copyOf(data("defense").propertyNames())).containsExactlyInAnyOrder("delta_vs_apm", "delta_cheese_index");
        assertThat(data("strength_split").isEmpty()).isTrue();                                       // 값이 없으면 빈 object
        assertThat(Set.copyOf(data("comeback_rate").propertyNames())).containsExactlyInAnyOrder(
                "comeback_rate", "comeback_rate_against", "comeback_opportunities", "comeback_won",
                "comeback_against_opportunities", "comeback_against_allowed");
        assertThat(data("comeback_rate").path("comeback_opportunities").asInt()).isEqualTo(41);
        assertThat(data("session_vs_slope").path("session_vs_slope").asDouble()).isEqualTo(0.55);
        // 라이벌은 앞에서 5명만, last_match_at 없이
        JsonNode rivals = data("rivals").path("rivals");
        assertThat(rivals.size()).isEqualTo(5);
        assertThat(Set.copyOf(rivals.get(0).propertyNames()))
                .containsExactlyInAnyOrder("nickname_masked", "matches", "wins", "losses");
        assertThat(rivals.get(0).path("nickname_masked").asString()).isEqualTo("pla***_0");
    }

    @Test
    void 콜드스타트면_엔진을_부르지_않고_8챕터_모두_failed() throws Exception {
        STATS.put("hv-cold", new Object[]{200, statsJson("hv-cold", true, 0)});

        List<Event> events = parse(get("/api/v1/comment/hv-cold?scope=heavy").body());

        List<Event> chapters = events.stream().filter(e -> e.name().equals("chapter")).toList();
        assertThat(chapters.stream().map(Event::id).toList()).containsExactlyElementsOf(CHAPTERS);
        assertThat(chapters).allSatisfy(c -> {
            assertThat(c.data().path("status").asString()).isEqualTo("failed");
            assertThat(c.data().has("footnote")).isFalse();
            assertThat(c.data().path("attempt_count").asInt(-1)).isEqualTo(0);
        });
        assertThat(events.getLast().data().path("completed").asInt()).isEqualTo(0);
        assertThat(events.getLast().data().path("failed_chapters").size()).isEqualTo(8);
        assertThat(ENGINE_REQUESTS).isEmpty();
    }

    @Test
    void 반복_조우_상대가_없으면_라이벌_챕터는_엔진_없이_고정_문구로_ok() throws Exception {
        STATS.put("hv-norival", new Object[]{200, statsJson("hv-norival", false, 0)});

        List<Event> events = parse(get("/api/v1/comment/hv-norival?scope=heavy").body());

        JsonNode rivals = chapter(events, "rivals");
        assertThat(rivals.path("status").asString()).isEqualTo("ok");
        assertThat(rivals.path("footnote").asString()).isEqualTo("5경기 이상 만난 상대가 아직 없어 상성을 판단하기 이릅니다.");
        assertThat(ENGINE_REQUESTS.stream().map(r -> r.path("section").asString()).toList())
                .hasSize(7).doesNotContain("rivals");
        assertThat(events.getLast().data().path("completed").asInt()).isEqualTo(8);
    }

    @Test
    void 엔진이_에러로_답한_챕터만_failed_시간_초과는_timeout() throws Exception {
        STATS.put("hv-fail", new Object[]{200, statsJson("hv-fail", false, 7)});
        BEHAVIOR.put("attack", "503");
        BEHAVIOR.put("defense", "502");
        BEHAVIOR.put("playstyle", "504");

        List<Event> events = parse(get("/api/v1/comment/hv-fail?scope=heavy").body());

        assertThat(events.stream().filter(e -> e.name().equals("chapter")).count()).isEqualTo(8);
        assertThat(chapter(events, "attack").path("status").asString()).isEqualTo("failed");
        assertThat(chapter(events, "attack").has("footnote")).isFalse();
        assertThat(chapter(events, "defense").path("status").asString()).isEqualTo("failed");
        assertThat(chapter(events, "playstyle").path("status").asString()).isEqualTo("timeout");
        assertThat(chapter(events, "tr_trend").path("status").asString()).isEqualTo("ok");
        JsonNode done = events.getLast().data();
        assertThat(done.path("completed").asInt()).isEqualTo(5);
        assertThat(texts(done.path("failed_chapters"))).containsExactly("playstyle", "attack", "defense");   // 챕터 순서대로
    }

    @Test
    void 성공한_챕터만_캐시하고_실패한_챕터는_다음_요청에_다시_부른다() throws Exception {
        STATS.put("hv-cache", new Object[]{200, statsJson("hv-cache", false, 7)});
        BEHAVIOR.put("attack", "503");
        get("/api/v1/comment/hv-cache?scope=heavy");
        assertThat(ENGINE_REQUESTS).hasSize(8);

        // 두 번째: 엔진이 돌아옴 → 실패했던 attack만 다시 부른다
        ENGINE_REQUESTS.clear();
        BEHAVIOR.clear();
        List<Event> second = parse(get("/api/v1/comment/hv-cache?scope=heavy").body());
        assertThat(ENGINE_REQUESTS.stream().map(r -> r.path("section").asString()).toList()).containsExactly("attack");
        assertThat(second.getLast().data().path("completed").asInt()).isEqualTo(8);

        // 세 번째: 전부 캐시 → 엔진을 부르지 않고 챕터 순서대로 바로
        ENGINE_REQUESTS.clear();
        List<Event> third = parse(get("/api/v1/comment/hv-cache?scope=heavy").body());
        assertThat(ENGINE_REQUESTS).isEmpty();
        assertThat(third.stream().filter(e -> e.name().equals("chapter")).map(Event::id).toList())
                .containsExactlyElementsOf(CHAPTERS);
        assertThat(chapter(third, "attack").path("footnote").asString()).isEqualTo("T:attack");
    }

    @Test
    void 생성_서버의_대체_문장은_보여_주되_캐시하지_않는다_rivals는_예외() throws Exception {
        STATS.put("hv-fb", new Object[]{200, statsJson("hv-fb", false, 7)});
        BEHAVIOR.put("attack", "fallback");
        BEHAVIOR.put("rivals", "fallback");   // rivals는 처음부터 서버 문장을 쓰는 챕터 — 다시 불러도 같으므로 캐시한다

        List<Event> first = parse(get("/api/v1/comment/hv-fb?scope=heavy").body());
        assertThat(chapter(first, "attack").path("status").asString()).isEqualTo("ok");
        assertThat(chapter(first, "attack").path("footnote").asString()).isEqualTo("T:attack");

        ENGINE_REQUESTS.clear();
        get("/api/v1/comment/hv-fb?scope=heavy");
        assertThat(ENGINE_REQUESTS.stream().map(r -> r.path("section").asString()).toList()).containsExactly("attack");
    }

    @Test
    void 전체_마감까지_못_끝난_챕터는_timeout으로_채워_정확히_8건() throws Exception {
        STATS.put("hv-slow", new Object[]{200, statsJson("hv-slow", false, 7)});
        BEHAVIOR.put("session_vs_slope", "slow");   // 6초 뒤 답 — 전체 마감(3초)을 넘긴다

        long started = System.nanoTime();
        List<Event> events = parse(get("/api/v1/comment/hv-slow?scope=heavy").body());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).as("마감에서 끊어야 한다").isLessThan(5500);
        List<Event> chapters = events.stream().filter(e -> e.name().equals("chapter")).toList();
        assertThat(chapters.stream().map(Event::id).toList()).containsExactlyInAnyOrderElementsOf(CHAPTERS);
        assertThat(chapter(events, "session_vs_slope").path("status").asString()).isEqualTo("timeout");
        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(texts(events.getLast().data().path("failed_chapters"))).containsExactly("session_vs_slope");
    }

    @Test
    void stats_실패는_error_이벤트_하나_보내고_닫는다() throws Exception {
        STATS.put("hv-busy", new Object[]{503,
                "{\"error_code\":\"SERVER_BUSY\",\"message\":\"사용자가 많습니다, 잠시 후 다시 시도\",\"retry_after_seconds\":5}"});
        STATS.put("hv-down", new Object[]{502,
                "{\"error_code\":\"TETRIO_API_UNAVAILABLE\",\"message\":\"일시적으로 조회할 수 없습니다, 잠시 후 다시 시도\"}"});
        STATS.put("x", new Object[]{400,
                "{\"error_code\":\"INVALID_USERNAME\",\"message\":\"유저명 형식이 올바르지 않습니다\"}"});

        List<Event> notFound = parse(get("/api/v1/comment/hv-nosuch?scope=heavy").body());
        assertThat(notFound.stream().map(Event::name).toList()).containsExactly("start", "error");
        assertThat(notFound.getLast().data()).isEqualTo(JSON.readTree(
                "{\"error_code\":\"USER_NOT_FOUND\",\"message\":\"존재하지 않는 TETR.IO 유저입니다\"}"));

        List<Event> busy = parse(get("/api/v1/comment/hv-busy?scope=heavy").body());
        assertThat(busy.getLast().data()).isEqualTo(JSON.readTree(
                "{\"error_code\":\"SERVER_BUSY\",\"message\":\"사용자가 많습니다, 잠시 후 다시 시도\",\"retry_after_seconds\":5}"));

        List<Event> down = parse(get("/api/v1/comment/hv-down?scope=heavy").body());
        assertThat(down.getLast().data().path("error_code").asString()).isEqualTo("TETRIO_API_UNAVAILABLE");

        // 모놀리식과 다른 점: 틀린 유저명은 스트림을 연 뒤 error 이벤트로 간다 (모놀리식은 스트림 전에 400)
        List<Event> invalid = parse(get("/api/v1/comment/x?scope=heavy").body());
        assertThat(invalid.getLast().name()).isEqualTo("error");
        assertThat(invalid.getLast().data().path("error_code").asString()).isEqualTo("INVALID_USERNAME");

        assertThat(ENGINE_REQUESTS).isEmpty();
    }

    // ---------- 도우미 ----------

    record Event(String name, String id, JsonNode data) {
    }

    /** SSE 본문을 이벤트 목록으로. 주석 줄(": ping")만 있는 덩어리는 건너뛴다 */
    static List<Event> parse(String body) {
        List<Event> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = null;
            String id = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
            if (name != null) {
                events.add(new Event(name, id, JSON.readTree(data.toString())));
            }
        }
        return events;
    }

    private static JsonNode chapter(List<Event> events, String id) {
        return events.stream().filter(e -> e.name().equals("chapter") && id.equals(e.id()))
                .findFirst().orElseThrow(() -> new AssertionError("chapter 이벤트 없음: " + id)).data();
    }

    private static JsonNode data(String section) {
        return ENGINE_REQUESTS.stream().filter(r -> r.path("section").asString().equals(section))
                .findFirst().orElseThrow(() -> new AssertionError("엔진이 받지 않은 섹션: " + section)).path("data");
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private HttpResponse<String> get(String pathAndQuery) throws IOException, InterruptedException {
        int port = env.getRequiredProperty("local.server.port", Integer.class);
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
