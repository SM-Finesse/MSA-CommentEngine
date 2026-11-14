package com.finesse.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
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
 * 같은 엔진에 칩 두 개를 꽂고 엔진 계약으로 불러 본다.
 *   demo   — template 모드. 생성 서버 없이 팩의 phrases로 문장을 만든다
 *   tetris — server 모드. 팩에 적힌 생성 서버(여기서는 가짜)를 부른다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommentEngineTest {

    static final StubGenServer GEN;

    static {
        try {
            GEN = new StubGenServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static final Path PACKS = TestPacks.withTetrisServers(GEN.url());

    @DynamicPropertySource
    static void packs(DynamicPropertyRegistry r) {
        r.add("engine.packs-dir", PACKS::toString);
    }

    @AfterAll
    static void stop() {
        GEN.close();
    }

    @Autowired
    Environment env;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private int port;

    @BeforeEach
    void setUp() {
        port = env.getRequiredProperty("local.server.port", Integer.class);
        GEN.received.clear();
    }

    @Test
    void demo팩_template방식으로_생성서버없이_문장이_나온다() throws Exception {
        var resp = Http.post(port, "/v1/comments/light", """
                {"domain":"demo","items":[{"metric":"metric_a","value":0.42,"zone":"high"},
                 {"metric":"metric_b","value":-3,"zone":"normal"}],"deadline_ms":30000}""");

        JsonNode body = json(resp, 200);
        assertThat(body.at("/items/0/sentence").asString()).isEqualTo("A가 높습니다(+0.42).");
        assertThat(body.at("/items/1/sentence").asString()).isEqualTo("B가 조금 적습니다(-3).");
        assertThat(body.at("/summary").asString()).isEqualTo("A가 높습니다. B가 조금 적습니다.");
        assertThat(body.at("/fallback").asBoolean()).isFalse();
        assertThat(body.at("/versions/pack").asString()).isEqualTo("0.0.1");
        assertThat(GEN.received).isEmpty();
    }

    @Test
    void demo팩_섹션은_no_data_문장() throws Exception {
        var resp = Http.post(port, "/v1/comments/section", """
                {"domain":"demo","section":"overview","data":{"metric_a":{"value":0,"zone":"normal"}},"deadline_ms":30000}""");

        JsonNode body = json(resp, 200);
        assertThat(body.at("/text").asString()).isEqualTo("설명할 값이 아직 충분하지 않습니다.");
        assertThat(body.at("/fallback").asBoolean()).isFalse();
    }

    @Test
    void tetris팩_server방식은_칸_이름을_바꿔_생성서버로_넘긴다() throws Exception {
        var resp = Http.post(port, "/v1/comments/light", """
                {"domain":"tetris","items":[{"metric":"delta_app","value":0.05,"zone":"high"}],"deadline_ms":35000}""");

        JsonNode body = json(resp, 200);
        assertThat(body.at("/items/0/sentence").asString()).isEqualTo("[stub] 문장");
        assertThat(body.at("/summary").asString()).isEqualTo("[stub] 요약");
        assertThat(body.at("/fallback").asBoolean()).isFalse();

        var sentence = GEN.received.stream().filter(r -> r.path().equals("/v1/light/sentence")).findFirst().orElseThrow();
        assertThat(mapper.readTree(sentence.body()))
                .isEqualTo(mapper.readTree("{\"stat\":\"delta_app\",\"value\":0.05,\"zone\":\"높음\"}"));
        var summary = GEN.received.stream().filter(r -> r.path().equals("/v1/light/summary")).findFirst().orElseThrow();
        assertThat(mapper.readTree(summary.body()).at("/items/0/stat").asString()).isEqualTo("delta_app");
    }

    @Test
    void tetris팩_섹션은_생성서버의_칸_이름으로_넘긴다() throws Exception {
        var resp = Http.post(port, "/v1/comments/section", """
                {"domain":"tetris","section":"attack","data":{"delta_app":0.05},"deadline_ms":50000}""");

        JsonNode body = json(resp, 200);
        assertThat(body.at("/section").asString()).isEqualTo("attack");
        assertThat(body.at("/text").asString()).isEqualTo("[stub] 섹션 문장");
        assertThat(body.at("/fallback").asBoolean()).isTrue();
        assertThat(mapper.readTree(GEN.received.getFirst().body()).at("/chapter_id").asString()).isEqualTo("attack");
    }

    @Test
    void 꽂힌_팩_목록() throws Exception {
        JsonNode body = json(Http.get(port, "/v1/domains"), 200);

        assertThat(body.at("/0/id").asString()).isEqualTo("demo");
        assertThat(body.at("/0/mode").asString()).isEqualTo("template");
        assertThat(body.at("/1/id").asString()).isEqualTo("tetris");
        assertThat(body.at("/1/mode").asString()).isEqualTo("server");
        assertThat(body.at("/1/spec_version").asString()).isEqualTo("0");
    }

    @Test
    void 에러_코드() throws Exception {
        assertError(Http.post(port, "/v1/comments/light", """
                {"domain":"lol","items":[{"metric":"a","value":1,"zone":"high"}],"deadline_ms":30000}"""),
                404, "UNKNOWN_DOMAIN");
        assertError(Http.post(port, "/v1/comments/light", """
                {"domain":"demo","items":[{"metric":"metric_x","value":1,"zone":"high"}],"deadline_ms":30000}"""),
                400, "INVALID_PAYLOAD");
        assertError(Http.post(port, "/v1/comments/light", """
                {"domain":"demo","items":[{"metric":"metric_a","value":1,"zone":"none"}],"deadline_ms":30000}"""),
                400, "INVALID_PAYLOAD");
        var r = Http.post(port, "/v1/comments/light", """
                {"domain":"demo","items":[{"metric":"metric_a","value":1,"zone":"high"}],"deadline_ms":0}""");
        assertError(r, 400, "INVALID_PAYLOAD");
        assertThat(mapper.readTree(r.body()).at("/message").asString()).startsWith("deadline_ms");
        assertError(Http.post(port, "/v1/comments/section", """
                {"domain":"demo","section":"nope","data":{},"deadline_ms":30000}"""),
                400, "INVALID_PAYLOAD");
    }

    private JsonNode json(HttpResponse<String> resp, int status) {
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(status);
        return mapper.readTree(resp.body());
    }

    private void assertError(HttpResponse<String> resp, int status, String code) {
        assertThat(json(resp, status).at("/error_code").asString()).isEqualTo(code);
    }
}
