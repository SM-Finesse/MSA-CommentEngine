package com.finesse.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.Zone;
import com.finesse.api.pack.DomainPack;
import com.finesse.api.pack.MetricDef;
import com.finesse.api.pack.Phrases;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 대체 문장 고르기 — 방향은 값의 부호, 강도는 영역 (슬롯 Phrases, 11/15 weak 칸 추가).
 * 문구는 분야와 상관없는 시험용 글자다.
 */
class TemplateGeneratorTest {

    static final Phrases FULL = new Phrases("H", "L", "N+", "N-", "Z", "W+", "W-");
    /** weak 칸이 없는 팩 (11/15 전에 만든 팩) */
    static final Phrases NO_WEAK = new Phrases("H", "L", "N+", "N-", "Z", null, null);

    @Test
    void 일곱_가지_상태() {
        assertEquals("H", TemplateGenerator.phrase(FULL, Zone.HIGH, 1));
        assertEquals("L", TemplateGenerator.phrase(FULL, Zone.LOW, -1));
        assertEquals("N+", TemplateGenerator.phrase(FULL, Zone.NORMAL, 1));
        assertEquals("N-", TemplateGenerator.phrase(FULL, Zone.NORMAL, -1));
        assertEquals("W+", TemplateGenerator.phrase(FULL, Zone.LOW, 1));   // 영역은 low인데 값은 양수
        assertEquals("W-", TemplateGenerator.phrase(FULL, Zone.HIGH, -1)); // 영역은 high인데 값은 음수
        for (Zone zone : Zone.values()) {
            assertEquals("Z", TemplateGenerator.phrase(FULL, zone, 0));
        }
    }

    @Test
    void weak_칸이_없으면_같은_부호의_normal_칸() {
        assertEquals("N+", TemplateGenerator.phrase(NO_WEAK, Zone.LOW, 1));
        assertEquals("N-", TemplateGenerator.phrase(NO_WEAK, Zone.HIGH, -1));
        // 나머지는 그대로
        assertEquals("H", TemplateGenerator.phrase(NO_WEAK, Zone.HIGH, 1));
        assertEquals("L", TemplateGenerator.phrase(NO_WEAK, Zone.LOW, -1));
    }

    @Test
    void 문장은_반올림한_값의_부호로_고르고_값을_붙인다() {
        TemplateGenerator gen = new TemplateGenerator();
        DomainPack pack = new DomainPack(null, Map.of("m", new MetricDef("지표", 2, FULL)), List.of());

        assertEquals("W-(-0.03).", gen.sentence(pack, new MetricItem("m", -0.031, Zone.HIGH)));
        assertEquals("H(+0.05).", gen.sentence(pack, new MetricItem("m", 0.054238, Zone.HIGH)));
        assertEquals("Z(0.00).", gen.sentence(pack, new MetricItem("m", 0.004, Zone.HIGH))); // 0.00으로 반올림
        assertEquals("W-. L.", gen.summary(pack, List.of( // 요약은 숫자 없이 문장을 이어 붙인다
                new MetricItem("m", -0.5, Zone.HIGH), new MetricItem("m", -0.5, Zone.LOW))));
    }
}
