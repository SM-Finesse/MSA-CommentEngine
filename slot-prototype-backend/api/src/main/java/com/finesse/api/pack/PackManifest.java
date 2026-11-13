package com.finesse.api.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * pack.json — 팩 표지 (설계 방안 6.2절, 규격 0판).
 *
 * @param specVersion 이 팩이 따르는 <b>규격</b>의 판 ("0")
 * @param version     팩 <b>내용</b>의 버전 ("2026.11")
 * @param generation  문장 만드는 방식 — legacy · template (generic은 예약)
 * @param light       라이트 후보 지표(앞일수록 우선)와 고를 개수 — 잠정 칸
 * @param noData      값이 없을 때 쓰는 문장
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PackManifest(
        String specVersion,
        String id,
        String version,
        String lang,
        Generation generation,
        Light light,
        List<Section> sections,
        String noData) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Generation(String mode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Light(List<String> candidates, Integer pick) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(String id, List<String> metrics) {
    }
}
