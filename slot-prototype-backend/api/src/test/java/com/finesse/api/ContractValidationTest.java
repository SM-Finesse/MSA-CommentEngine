package com.finesse.api;

import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.Zone;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 시험 2 — 검증. 빈 items, deadline_ms: 0, zone: "none", value: NaN은 거절, deadline_ms: 40000은 통과 */
class ContractValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final MetricItem OK_ITEM = new MetricItem("metric_a", 0.42, Zone.HIGH);

    @Test
    void 정상_요청은_통과_40초도_통과() {
        assertThat(validate(new LightCommentRequest("demo", List.of(OK_ITEM), 40000))).isEmpty();
    }

    @Test
    void 빈_items는_거절() {
        assertThat(paths(validate(new LightCommentRequest("demo", List.of(), 30000)))).contains("items");
    }

    @Test
    void deadline_0은_거절_120초_초과도_거절() {
        assertThat(paths(validate(new LightCommentRequest("demo", List.of(OK_ITEM), 0)))).contains("deadlineMs");
        assertThat(paths(validate(new LightCommentRequest("demo", List.of(OK_ITEM), 120001)))).contains("deadlineMs");
    }

    @Test
    void NaN_값은_거절() {
        var req = new LightCommentRequest("demo", List.of(new MetricItem("metric_a", Double.NaN, Zone.HIGH)), 30000);
        assertThat(paths(validate(req))).anyMatch(p -> p.startsWith("items[0]"));
    }

    @Test
    void zone_none은_JSON을_읽는_단계에서_거절() {
        String json = """
                {"domain":"demo","items":[{"metric":"metric_a","value":0.1,"zone":"none"}],"deadline_ms":30000}""";
        assertThatThrownBy(() -> mapper.readValue(json, LightCommentRequest.class))
                .isInstanceOf(JacksonException.class);
    }

    private Set<ConstraintViolation<LightCommentRequest>> validate(LightCommentRequest req) {
        return validator.validate(req);
    }

    private static List<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).toList();
    }
}
