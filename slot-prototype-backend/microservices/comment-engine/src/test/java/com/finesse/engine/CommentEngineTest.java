package com.finesse.engine;

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
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완료 기준 — demo 팩을 꽂아 /v1/comments/light가 엔진 코드 수정 없이 문장을 돌려준다.
 * 같은 엔진이 tetris 팩(legacy)이면 가짜 llm-server로 칸 이름만 바꿔 넘기는지도 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.packs-dir=../../domain-packs")
class CommentEngineTest {

    static final StubLlmServer LLM;

    static {
        try {
            LLM = new StubLlmServer(Map.of(
                    "/v1/light/sentence", "{\"stat\":\"x\",\"sentence\":\"[stub] 문장\",\"fallback\":false,\"version\":\"stub\"}",
                    "/v1/light/summary", "{\"light_summary\":\"[stub] 요약\",\"fallback\":false,\"version\":\"stub\"}",
                    "/v1/comment/heavy-chapter", "{\"chapter_id\":\"attack\",\"footnote\":\"[stub] 섹션 문장\",\"fallback\":true}"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void llm(DynamicPropertyRegistry r) {
        r.add("engine.legacy.servers", LLM::url);
    }

    @AfterAll
    static void stop() {
        LLM.close();
    }

    @Autowired
    Environment env;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private int port;

    @BeforeEach
    void setUp() {
        port = env.getRequiredProperty("local.server.port", Integer.class);
        LLM.received.clear();
    }

    @Test
    void demo팩_template방식으로_LLM없이_문장이_나온다() throws Exception {
        var resp = Http.post(port, "/v1/comments/light", """
                {"domain":"demo","items":[{"metric":"metric_a","value":0.42,"zone":"high"},
                 {"metric":"metric_b","value":-3,"zone":"normal"}],"deadline_ms":30000}""");
        JsonNode body = json(resp, 200);
        assertThat(body.at("/items/0/sentence").asString()).isEqualTo("A가 높습니다");
        assertThat(body.at("/items/1/sentence").asString()).isEqualTo("B가 조금 적습니다");
        assertThat(body.at("/summary").asString()).isEqualTo("A가 높습니다, B가 조금 적습니다");
        assertThat(body.at("/versions/pack").asString()).isEqualTo("0.0.1");
        assertThat(LLM.received).isEmpty();
    }

    @Test
    void demo팩_섹션() throws Exception {
        var resp = Http.post(port, "/v1/comments/section", """
                {"domain":"demo","section":"overview","data":{"metric_a":{"value":0,"zone":"normal"}},"deadline_ms":30000}""");
        assertThat(json(resp, 200).at("/text").asString()).isEqualTo("A는 변화가 없습니다");
    }

    @Test
    void tetris팩_legacy방식은_칸_이름만_바꿔_llm서버로_넘긴다() throws Exception {
        var resp = Http.post(port, "/v1/comments/light", """
                {"domain":"tetris","items":[{"metric":"delta_app","value":0.05,"zone":"high"}],"deadline_ms":35000}""");
        JsonNode body = json(resp, 200);
        assertThat(body.at("/items/0/sentence").asString()).isEqualTo("[stub] 문장");
        assertThat(body.at("/summary").asString()).isEqualTo("[stub] 요약");
        assertThat(body.at("/fallback").asBoolean()).isFalse();

        var sentence = LLM.received.stream().filter(r -> r.path().equals("/v1/light/sentence")).findFirst().orElseThrow();
        assertThat(mapper.readTree(sentence.body()))
                .isEqualTo(mapper.readTree("{\"stat\":\"delta_app\",\"value\":0.05,\"zone\":\"높음\"}"));
        var summary = LLM.received.stream().filter(r -> r.path().equals("/v1/light/summary")).findFirst().orElseThrow();
        assertThat(mapper.readTree(summary.body()).at("/items/0/stat").asString()).isEqualTo("delta_app");
    }

    @Test
    void tetris팩_섹션은_설정의_칸_이름으로_넘긴다() throws Exception {
        var resp = Http.post(port, "/v1/comments/section", """
                {"domain":"tetris","section":"attack","data":{"delta_app":0.05},"deadline_ms":50000}""");
        JsonNode body = json(resp, 200);
        assertThat(body.at("/text").asString()).isEqualTo("[stub] 섹션 문장");
        assertThat(body.at("/fallback").asBoolean()).isTrue();
        assertThat(mapper.readTree(LLM.received.getFirst().body()).at("/chapter_id").asString()).isEqualTo("attack");
    }

    @Test
    void 꽂힌_팩_목록() throws Exception {
        JsonNode body = json(Http.get(port, "/v1/domains"), 200);
        assertThat(body.findValuesAsString("id")).containsExactly("demo", "tetris");
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
