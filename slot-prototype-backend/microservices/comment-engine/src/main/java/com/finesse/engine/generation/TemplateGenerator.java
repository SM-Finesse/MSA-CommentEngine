package com.finesse.engine.generation;

import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import com.finesse.api.engine.dto.Versions;
import com.finesse.api.engine.dto.Zone;
import com.finesse.api.pack.DomainPack;
import com.finesse.api.pack.MetricDef;
import com.finesse.api.pack.PackManifest;
import com.finesse.engine.EngineProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * template 방식 — LLM 없이 팩 metrics.json의 phrases로 문장을 조립한다 (설계 이해 6.5절).
 * <pre>
 * value = 0              → zero
 * zone = high            → high
 * zone = low             → low
 * zone = normal, value>0 → normal_pos
 * zone = normal, value<0 → normal_neg
 * </pre>
 * 프로토타입 결정: 문장에 값은 넣지 않는다(decimals는 아직 안 씀). 요약은 지표 문장을 ", "로 잇는다.
 */
@Component
public class TemplateGenerator {

    private final EngineProperties properties;

    public TemplateGenerator(EngineProperties properties) {
        this.properties = properties;
    }

    public LightCommentResult light(LightCommentRequest request, DomainPack pack) {
        List<LightCommentResult.ItemSentence> items = request.items().stream()
                .map(i -> new LightCommentResult.ItemSentence(i.metric(),
                        phrase(pack.metric(i.metric()).orElseThrow(), i.zone(), i.value()), false))
                .toList();
        String summary = String.join(", ", items.stream().map(LightCommentResult.ItemSentence::sentence).toList());
        return new LightCommentResult(summary, items, false, versions(pack));
    }

    /**
     * 섹션 — data는 자유 형식이지만 template 방식은 {@code { "<지표>": { "value": 0.42, "zone": "high" } }}를 기대한다
     * (프로토타입 결정, 설계 방안 10장 미결 3). 섹션의 지표 중 이 모양으로 온 것만 문장으로 만들고, 하나도 없으면 no_data.
     */
    public SectionCommentResult section(SectionCommentRequest request, PackManifest.Section section, DomainPack pack) {
        List<String> parts = new ArrayList<>();
        for (String metric : section.metrics()) {
            if (request.data().get(metric) instanceof Map<?, ?> m
                    && m.get("value") instanceof Number value
                    && m.get("zone") instanceof String zone) {
                Zone z = parseZone(zone);
                if (z != null && Double.isFinite(value.doubleValue())) {
                    parts.add(phrase(pack.metric(metric).orElseThrow(), z, value.doubleValue()));
                }
            }
        }
        String text = parts.isEmpty() ? pack.manifest().noData() : String.join(", ", parts);
        return new SectionCommentResult(section.id(), text, false, versions(pack));
    }

    static String phrase(MetricDef def, Zone zone, double value) {
        MetricDef.Phrases p = def.phrases();
        if (value == 0) {
            return p.zero();
        }
        return switch (zone) {
            case HIGH -> p.high();
            case LOW -> p.low();
            case NORMAL -> value > 0 ? p.normalPos() : p.normalNeg();
        };
    }

    private static Zone parseZone(String s) {
        for (Zone z : Zone.values()) {
            if (z.wire().equals(s)) {
                return z;
            }
        }
        return null;
    }

    private Versions versions(DomainPack pack) {
        return new Versions(properties.version(), pack.manifest().version());
    }
}
