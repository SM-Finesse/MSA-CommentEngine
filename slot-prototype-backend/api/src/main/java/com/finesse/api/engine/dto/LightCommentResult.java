package com.finesse.api.engine.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * POST /v1/comments/light 응답.
 *
 * @param fallback 요약이 대체 문장인지 (지표별 대체 여부는 items[].fallback)
 * @param versions 어떤 엔진 · 팩으로 만들었는지 — 추적용
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LightCommentResult(String summary, List<ItemSentence> items, boolean fallback, Versions versions) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemSentence(String metric, String sentence, boolean fallback) {
    }
}
