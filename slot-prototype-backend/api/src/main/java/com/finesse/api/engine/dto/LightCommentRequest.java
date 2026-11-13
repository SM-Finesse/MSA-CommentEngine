package com.finesse.api.engine.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * POST /v1/comments/light 요청.
 *
 * @param domain     어느 팩을 쓸지
 * @param items      문장을 붙일 지표 목록
 * @param deadlineMs 부르는 쪽 마감까지 <b>남은 시간</b> (JSON: deadline_ms). 1~120초 — 상한은 잘못된 값 거르기용
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true) // 모르는 칸은 무시 — 부르는 쪽이 칸을 먼저 추가해도 깨지지 않게
public record LightCommentRequest(
        @NotBlank String domain,
        @NotEmpty @Size(max = 32) List<@NotNull @Valid MetricItem> items,
        @Min(1000) @Max(120000) int deadlineMs) {
}
