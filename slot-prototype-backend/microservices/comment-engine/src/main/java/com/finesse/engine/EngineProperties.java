package com.finesse.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * @param version  응답 versions.engine
 * @param packsDir 팩 폴더 (docker에서는 ./domain-packs:/packs:ro로 꽂는 자리)
 * @param legacy   legacy 방식 — 지금 llm-server로 넘길 때의 주소 · 경로 · 칸 이름.
 *                 llm-server 쪽 이름은 코드가 아니라 설정에 둔다(엔진 코드에 분야 단어를 두지 않기 위해)
 */
@ConfigurationProperties("engine")
public record EngineProperties(String version, String packsDir, Legacy legacy) {

    /**
     * 지금 백엔드 LlmClient의 라이트 규칙을 그대로 옮긴 값 (application.yml llm.light-comment).
     *
     * @param servers          LLM 서버 목록 — 문장은 첫 서버, 요약은 둘째 서버로 (서버 1대면 같은 서버)
     * @param sectionField     섹션 이름을 담을 llm-server 칸 이름
     * @param sectionTextField llm-server 응답에서 섹션 문장이 든 칸 이름
     * @param zoneLabels       엔진 zone → llm-server가 받는 영역 글자
     * @param callTimeout      호출 1회 상한 (남은 시간이 더 짧으면 남은 시간)
     * @param minRemaining     다른 서버로 다시 부르려면 남아 있어야 하는 시간
     * @param retryAfter       LLM_UNAVAILABLE 응답의 Retry-After
     */
    public record Legacy(
            List<String> servers,
            String sentencePath,
            String summaryPath,
            String sectionPath,
            String sectionField,
            String sectionTextField,
            Map<String, String> zoneLabels,
            Duration connectTimeout,
            Duration callTimeout,
            Duration minRemaining,
            Duration retryAfter) {
    }
}
