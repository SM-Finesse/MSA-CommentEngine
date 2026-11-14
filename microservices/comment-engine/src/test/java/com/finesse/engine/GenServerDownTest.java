package com.finesse.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** 칩의 생성 서버가 전부 꺼져 있으면 그 칩은 503 LLM_UNAVAILABLE + Retry-After. 다른 칩(template)은 그대로 동작 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenServerDownTest {

    static final Path PACKS = TestPacks.withTetrisServers("http://127.0.0.1:1", "http://127.0.0.1:2");

    @DynamicPropertySource
    static void packs(DynamicPropertyRegistry r) {
        r.add("engine.packs-dir", PACKS::toString);
    }

    @Autowired
    Environment env;

    @Test
    void server칩은_503_template칩은_정상() throws Exception {
        int port = env.getRequiredProperty("local.server.port", Integer.class);

        var down = Http.post(port, "/v1/comments/light", """
                {"domain":"tetris","items":[{"metric":"delta_app","value":0.05,"zone":"high"}],"deadline_ms":30000}""");
        assertThat(down.statusCode()).as(down.body()).isEqualTo(503);
        assertThat(down.headers().firstValue("Retry-After")).hasValue("30");
        assertThat(JsonMapper.builder().build().readTree(down.body()).at("/error_code").asString())
                .isEqualTo("LLM_UNAVAILABLE");

        var demo = Http.post(port, "/v1/comments/light", """
                {"domain":"demo","items":[{"metric":"metric_a","value":0.42,"zone":"high"}],"deadline_ms":30000}""");
        assertThat(demo.statusCode()).as(demo.body()).isEqualTo(200);
    }
}
