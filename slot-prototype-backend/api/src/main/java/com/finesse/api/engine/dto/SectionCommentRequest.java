package com.finesse.api.engine.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.Map;

/**
 * POST /v1/comments/section 요청.
 *
 * @param section 팩 pack.json의 sections[].id
 * @param data    자유 형식 — 모양은 칩이 정한다 (설계 방안 10장 미결 3). 엔진은 검사 없이 넘긴다
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SectionCommentRequest(
        @NotBlank String domain,
        @NotBlank String section,
        @NotNull Map<String, Object> data,
        @Min(1000) @Max(120000) int deadlineMs) {
}
