package com.finesse.api.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * metrics.json 원소 — 지표 사전 한 칸 (설계 방안 6.3절).
 *
 * @param label       문장에 쓰는 이름
 * @param decimals    소수 몇 자리까지 (0~4)
 * @param normalRange 보통 범위 {q1, q3}. null이면 부호만 봄
 * @param reference   opponent(상대 대비) · self(내 기록) — 잠정 칸
 * @param phrases     상황별 문장 5칸 — template 팩에서 필수, legacy 팩은 비워도 됨. 잠정 칸
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetricDef(String label, Integer decimals, NormalRange normalRange, String reference, Phrases phrases) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NormalRange(Double q1, Double q3) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Phrases(String high, String low, String normalPos, String normalNeg, String zero) {
    }
}
