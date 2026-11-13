package com.finesse.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** llm-server가 전부 꺼져 있으면 legacy 팩은 503 LLM_UNAVAILABLE + Retry-After. template 팩은 상관없이 동작 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "engine.packs-dir=../../domain-packs",
        "engine.legacy.servers=http://127.0.0.1:1,http://127.0.0.1:2"})
class LlmDownTest {

    @Autowired
    Environment env;

    @Test
    void legacy는_503_template은_정상() throws Exception {
        int port = env.getRequiredProperty("local.server.port", Integer.class);

        var down = Http.post(port, "/v1/comments/light", """
                {"domain":"tetris","items":[{"metric":"delta_app","value":0.05,"zone":"high"}],"deadline_ms":30000}""");
        assertThat(down.statusCode()).as(down.body()).isEqualTo(503);
        assertThat(down.headers().firstValue("Retry-After")).hasValue("5");
        assertThat(JsonMapper.builder().build().readTree(down.body()).at("/error_code").asString())
                .isEqualTo("LLM_UNAVAILABLE");

        var demo = Http.post(port, "/v1/comments/light", """
                {"domain":"demo","items":[{"metric":"metric_a","value":0.42,"zone":"high"}],"deadline_ms":30000}""");
        assertThat(demo.statusCode()).isEqualTo(200);
    }
}
