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
 * tetris 칩의 대체 문장 자체(지표 11개, 영역과 부호가 반대일 때의 weak 문장)도 여기서 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenServerDownTest {

    static final Path PACKS = TestPacks.withTetrisServers("http://127.0.0.1:1", "http://127.0.0.1:2");

    @DynamicPropertySource
    static void packs(DynamicPropertyRegistry r) {
        r.add("engine.chips-dir", PACKS::toString);
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

    /** calc가 고를 수 있는 하이라이트 후보 11개 (모놀리식 CommentService.displayValue) — tetris 칩에 모두 있어야 한다 */
    static final java.util.List<String> CANDIDATES = java.util.List.of("delta_opener", "delta_plonk", "delta_stride",
            "delta_inf_ds", "delta_app", "delta_weighted_app", "delta_vs_apm", "delta_cheese_index", "strength_split",
            "delta_comeback", "session_vs_slope");

    @Test
    void tetris칩은_하이라이트_후보_11개를_모두_받는다() throws Exception {
        String items = CANDIDATES.stream()
                .map(k -> "{\"metric\":\"" + k + "\",\"value\":0.2,\"zone\":\"normal\"}")
                .collect(java.util.stream.Collectors.joining(","));
        var resp = Http.post(port(), "/v1/comments/light",
                "{\"domain\":\"tetris\",\"items\":[" + items + "],\"deadline_ms\":30000}");

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = mapper.readTree(resp.body());
        assertThat(body.at("/items").size()).isEqualTo(11);
        for (int i = 0; i < CANDIDATES.size(); i++) {
            assertThat(body.at("/items/" + i + "/metric").asString()).isEqualTo(CANDIDATES.get(i));
            // 표시 자릿수는 가비지 처리 성향만 1자리, 나머지 2자리 (모놀리식 LightCommentTemplates.rounded와 같음)
            String shown = CANDIDATES.get(i).equals("delta_cheese_index") ? "(+0.2)." : "(+0.20).";
            assertThat(body.at("/items/" + i + "/sentence").asString()).endsWith(shown);
        }
    }

    @Test
    void 영역과_부호가_반대면_weak_문장_반올림해_0이면_zero_문장() throws Exception {
        var resp = Http.post(port(), "/v1/comments/light", """
                {"domain":"tetris","items":[
                  {"metric":"strength_split","value":-0.1,"zone":"high"},
                  {"metric":"delta_vs_apm","value":0.12,"zone":"low"},
                  {"metric":"session_vs_slope","value":0.003,"zone":"normal"}],"deadline_ms":30000}""");

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = mapper.readTree(resp.body());
        // 모놀리식 LightCommentTemplates와 같은 문장 (비교 표현은 "상대 대비" 하나)
        assertThat(body.at("/items/0/sentence").asString())
                .isEqualTo("강한 상대를 만나도 약한 상대 때와 비교해 승률이 크게 흔들리지 않는 편입니다(-0.10).");
        assertThat(body.at("/items/1/sentence").asString())
                .isEqualTo("상대 대비 수비 비중이 조금 큰 편이지만 그 폭은 크지 않습니다(+0.12).");
        assertThat(body.at("/items/2/sentence").asString())
                .isEqualTo("라운드가 진행되어도 공방 능력에 뚜렷한 추세가 없습니다(0.00).");
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
