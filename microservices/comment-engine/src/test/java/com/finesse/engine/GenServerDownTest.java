package com.finesse.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 칩의 생성 서버가 전부 꺼져 있을 때.
 *   light   → 에러 대신 팩 phrases 대체 문장으로 200, fallback = true (모놀리식 light-v5.2와 같음)
 *   section → 503 LLM_UNAVAILABLE + Retry-After (모놀리식 heavy 챕터 failed와 대응)
 *   다른 칩(template)은 그대로 동작
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenServerDownTest {

    static final Path PACKS = TestPacks.withTetrisServers("http://127.0.0.1:1", "http://127.0.0.1:2");

    @DynamicPropertySource
    static void packs(DynamicPropertyRegistry r) {
        r.add("engine.packs-dir", PACKS::toString);
    }

    @Autowired
    Environment env;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void light는_대체_문장으로_200() throws Exception {
        int port = port();
        var resp = Http.post(port, "/v1/comments/light", """
                {"domain":"tetris","items":[{"metric":"delta_app","value":0.05,"zone":"high"}],"deadline_ms":30000}""");

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.at("/fallback").asBoolean()).isTrue();
        assertThat(body.at("/items/0/fallback").asBoolean()).isTrue();
        assertThat(body.at("/items/0/sentence").asString()).isEqualTo("상대 대비 블록당 공격력에서 뚜렷하게 앞섭니다(+0.05).");
        assertThat(body.at("/summary").asString()).isEqualTo("상대 대비 블록당 공격력에서 뚜렷하게 앞섭니다.");
    }

    @Test
    void section은_503() throws Exception {
        var resp = Http.post(port(), "/v1/comments/section", """
                {"domain":"tetris","section":"attack","data":{"delta_app":0.05},"deadline_ms":30000}""");

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(503);
        assertThat(resp.headers().firstValue("Retry-After")).hasValue("30");
        assertThat(mapper.readTree(resp.body()).at("/error_code").asString()).isEqualTo("LLM_UNAVAILABLE");
    }

    @Test
    void template칩은_그대로() throws Exception {
        var demo = Http.post(port(), "/v1/comments/light", """
                {"domain":"demo","items":[{"metric":"metric_a","value":0.42,"zone":"high"}],"deadline_ms":30000}""");

        assertThat(demo.statusCode()).as(demo.body()).isEqualTo(200);
        assertThat(mapper.readTree(demo.body()).at("/fallback").asBoolean()).isFalse();
    }

    private int port() {
        return env.getRequiredProperty("local.server.port", Integer.class);
    }
}
