package com.finesse.composite.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 데모 입력 — 백엔드 stats 응답 highlight_judgement의 선정 결과와 같은 모양.
 *
 * @param domain     어느 팩 (tetris · demo)
 * @param highlights 선정된 지표들. zone은 high · normal · low · none
 */
public record DemoLightRequest(@NotBlank String domain, @NotNull List<@Valid Pick> highlights) {

    public record Pick(@NotBlank String stat, double value,
                       @NotNull @Pattern(regexp = "high|normal|low|none") String zone) {
    }
}
