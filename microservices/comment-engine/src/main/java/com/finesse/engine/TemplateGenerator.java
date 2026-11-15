package com.finesse.engine;

import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.Zone;
import com.finesse.api.pack.DomainPack;
import com.finesse.api.pack.MetricDef;
import com.finesse.api.pack.Phrases;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 팩의 phrases로 문장을 조립한다. 두 군데서 쓴다.
 *  - template 모드 팩: 모든 문장
 *  - server 모드 팩: 생성 서버가 실패한 항목의 대체 문장
 */
@Component
public class TemplateGenerator {

    /** 확정안 7.1절 — 값(표시 자릿수로 반올림)이 0이면 zero, 아니면 영역과 부호로 고른다 (11/15 weak 칸 추가) */
    public String sentence(DomainPack pack, MetricItem item) {
        MetricDef def = pack.metrics().get(item.metric());
        BigDecimal shown = BigDecimal.valueOf(item.value()).setScale(def.decimals(), RoundingMode.HALF_EVEN);
        return phrase(def.phrases(), item.zone(), shown.signum()) + "(" + format(shown) + ").";
    }

    /** 요약: 지표별 문장을 숫자 없이 이어 붙인다 */
    public String summary(DomainPack pack, List<MetricItem> items) {
        return items.stream().map(item -> {
            MetricDef def = pack.metrics().get(item.metric());
            int sign = BigDecimal.valueOf(item.value()).setScale(def.decimals(), RoundingMode.HALF_EVEN).signum();
            return phrase(def.phrases(), item.zone(), sign) + ".";
        }).collect(Collectors.joining(" "));
    }

    /** 섹션 대체 문장은 no_data */
    public String section(DomainPack pack) {
        return pack.manifest().noData();
    }

    /**
     * 방향은 값의 부호, 강도는 영역 ({@link Phrases} 설명의 표). 영역과 부호가 반대면 weak 칸,
     * 팩에 weak 칸이 없으면 같은 부호의 normal 칸.
     */
    static String phrase(Phrases p, Zone zone, int sign) {
        if (sign == 0) {
            return p.zero();
        }
        return switch (zone) {
            case HIGH -> sign > 0 ? p.high() : orElse(p.weakNeg(), p.normalNeg());
            case LOW -> sign < 0 ? p.low() : orElse(p.weakPos(), p.normalPos());
            case NORMAL -> sign > 0 ? p.normalPos() : p.normalNeg();
        };
    }

    private static String orElse(String optional, String fallback) {
        return optional != null ? optional : fallback;
    }

    private static String format(BigDecimal shown) {
        return (shown.signum() > 0 ? "+" : "") + shown.toPlainString();
    }
}
