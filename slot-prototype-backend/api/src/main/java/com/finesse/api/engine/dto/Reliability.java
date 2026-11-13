package com.finesse.api.engine.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 선택 필드 — 백엔드 stats 응답의 지표별 reliability 한 칸과 같은 모양(11/2).
 * 1단계에서 엔진은 쓰지 않는다 (설계 방안 10장 미결 1).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Reliability(String metricLevel, Boolean lowSample, Boolean suspicious) {
}
