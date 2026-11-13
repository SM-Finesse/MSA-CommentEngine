package com.finesse.api;

import com.finesse.api.engine.dto.DomainInfo;
import com.finesse.api.engine.dto.EngineError;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import com.finesse.api.engine.dto.Zone;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 시험 1 — DTO ↔ JSON 왕복. 고정 예시 JSON을 읽고 다시 쓰면 같고, 키가 모두 snake_case */
class ContractJsonTest {

    // 앱 설정 없이 기본 mapper — snake_case는 DTO 자신(@JsonNaming)이 들고 있어야 한다
    private final JsonMapper mapper = JsonMapper.builder().build();

    static final String LIGHT_REQUEST = """
            {"domain":"demo","items":[{"metric":"metric_a","value":0.42,"zone":"high"},\
            {"metric":"metric_b","value":-3.0,"zone":"normal","reliability":{"metric_level":"caution","low_sample":true}}],\
            "deadline_ms":30000}""";
    static final String LIGHT_RESULT = """
            {"summary":"요약","items":[{"metric":"metric_a","sentence":"A가 높습니다","fallback":false}],\
            "fallback":false,"versions":{"engine":"0.1.0","pack":"0.0.1"}}""";
    static final String SECTION_REQUEST = """
            {"domain":"demo","section":"overview","data":{"metric_a":{"value":0.42,"zone":"high"}},"deadline_ms":50000}""";
    static final String SECTION_RESULT = """
            {"section":"overview","text":"A가 높습니다","fallback":false,"versions":{"engine":"0.1.0","pack":"0.0.1"}}""";
    static final String DOMAIN_INFO = """
            {"id":"demo","version":"0.0.1","spec_version":"0","mode":"template","sections":["overview"]}""";
    static final String ERROR = """
            {"error_code":"UNKNOWN_DOMAIN","message":"domain 'lol' is not loaded"}""";

    @Test
    void 왕복하면_같은_JSON() {
        assertRoundTrip(SECTION_REQUEST, SectionCommentRequest.class);
        assertRoundTrip(LIGHT_RESULT, LightCommentResult.class);
        assertRoundTrip(SECTION_RESULT, SectionCommentResult.class);
        assertRoundTrip(DOMAIN_INFO, DomainInfo.class);
        assertRoundTrip(ERROR, EngineError.class);
        // reliability의 빠진 칸(suspicious)은 null로 나가므로 트리 비교 대신 값으로 확인
        LightCommentRequest req = mapper.readValue(LIGHT_REQUEST, LightCommentRequest.class);
        assertThat(req.deadlineMs()).isEqualTo(30000);
        assertThat(req.items().get(0).zone()).isEqualTo(Zone.HIGH);
        assertThat(req.items().get(1).reliability().metricLevel()).isEqualTo("caution");
        assertThat(mapper.readValue(mapper.writeValueAsString(req), LightCommentRequest.class)).isEqualTo(req);
    }

    @Test
    void 키는_전부_snake_case() {
        for (Object dto : List.of(
                mapper.readValue(LIGHT_REQUEST, LightCommentRequest.class),
                mapper.readValue(LIGHT_RESULT, LightCommentResult.class),
                mapper.readValue(SECTION_REQUEST, SectionCommentRequest.class),
                mapper.readValue(DOMAIN_INFO, DomainInfo.class),
                mapper.readValue(ERROR, EngineError.class))) {
            String json = mapper.writeValueAsString(dto);
            assertThat(keys(json)).as(json).allMatch(k -> k.matches("[a-z0-9_]+"));
            assertThat(json).doesNotContain("valueFinite").doesNotContain("value_finite");
        }
    }

    @Test
    void 모르는_칸은_무시한다() {
        // 부르는 쪽이 "lang" 칸을 먼저 추가해 배포해도 옛 엔진이 깨지지 않는다 (5.5절)
        LightCommentRequest req = mapper.readValue(
                LIGHT_REQUEST.replace("\"deadline_ms\"", "\"lang\":\"en\",\"deadline_ms\""), LightCommentRequest.class);
        assertThat(req.domain()).isEqualTo("demo");
    }

    private void assertRoundTrip(String json, Class<?> type) {
        Object dto = mapper.readValue(json, type);
        JsonNode expected = mapper.readTree(json);
        JsonNode actual = mapper.readTree(mapper.writeValueAsString(dto));
        assertThat(actual).as(type.getSimpleName()).isEqualTo(expected);
    }

    private static List<String> keys(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
