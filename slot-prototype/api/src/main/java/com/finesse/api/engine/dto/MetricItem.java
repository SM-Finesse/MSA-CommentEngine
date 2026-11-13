package com.finesse.api.engine.dto;

/** 지표 1개. 고르기와 zone 계산은 칩이 이미 끝낸 상태로 온다 */
public record MetricItem(String metric, Double value, Zone zone) {
}
