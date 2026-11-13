package com.finesse.api.engine;

/**
 * 엔진 에러 코드 5개 (설계 방안 5.4절). 본문은 {@code { "error_code": "...", "message": "..." }}.
 * 화면에 나가는 이름(LLM_FORMAT_ERROR 등)으로 바꾸는 일은 finesse-api 한 곳에서 한다.
 */
public enum EngineErrorCode {

    /** 칸 누락, 모르는 metric · section, NaN 값 — 다시 보내도 또 틀림 */
    INVALID_PAYLOAD(400, false),
    /** 꽂히지 않은 domain */
    UNKNOWN_DOMAIN(404, false),
    /** LLM 응답 형식이 계속 이상함 */
    LLM_FORMAT(502, false),
    /** LLM 서버가 잠깐 못 받음 — 유일하게 다시 시도. Retry-After 헤더로 몇 초 뒤인지 알림 */
    LLM_UNAVAILABLE(503, true),
    /** 시간 초과 — 또 보내면 LLM 부하가 두 배라 다시 시도하지 않음 */
    LLM_TIMEOUT(504, false);

    private final int httpStatus;
    private final boolean retryable;

    EngineErrorCode(int httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    /** 모르는 코드는 null — 부르는 쪽은 일반 오류로 처리 (5.5절 "에러 코드 추가 허용") */
    public static EngineErrorCode fromName(String name) {
        for (EngineErrorCode c : values()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }
}
