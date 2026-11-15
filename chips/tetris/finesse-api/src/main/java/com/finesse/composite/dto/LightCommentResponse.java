package com.finesse.composite.dto;

import java.util.List;

/** 지금 백엔드 light 응답과 같은 모양 — { "light_summary": ..., "highlights": [{ "stat", "sentence" }] } */
public record LightCommentResponse(String lightSummary, List<Highlight> highlights) {

    public record Highlight(String stat, String sentence) {
    }
}
