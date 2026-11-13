package com.finesse.api.generation.dto;

/**
 * ③ 생성 서버 규약 = 지금 llm-server API를 그대로 동결 (확정안 5절).
 * zone은 llm-server가 받는 값(높음 · 보통 · 낮음)으로 보낸다.
 */
public record SentenceRequest(String stat, double value, String zone) {
}
