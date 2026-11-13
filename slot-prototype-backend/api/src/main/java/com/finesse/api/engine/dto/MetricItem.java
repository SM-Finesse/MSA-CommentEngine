package com.finesse.api.engine.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 지표 하나.
 *
 * @param metric      팩의 metrics.json 키. 엔진에게는 그냥 문자열
 * @param value       원값 그대로 (반올림하지 않음). NaN · Infinity는 거절
 * @param zone        high · normal · low
 * @param reliability 선택 — 뜻이 정해질 때까지(설계 방안 10장 미결 1) 엔진은 받기만 하고 쓰지 않음
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetricItem(
        @NotBlank String metric,
        double value,
        @NotNull Zone zone,
        Reliability reliability) {

    public MetricItem(String metric, double value, Zone zone) {
        this(metric, value, zone, null);
    }

    @JsonIgnore
    @AssertTrue(message = "value must be a finite number")
    public boolean isValueFinite() {
        return Double.isFinite(value);
    }
}
