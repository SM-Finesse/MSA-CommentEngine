package com.finesse.api.engine;

/** ① 엔진 계약의 에러 코드 5개 (확정안 3.4절). 본문은 {error_code, message} */
public enum EngineErrorCode {
    INVALID_PAYLOAD(400, false),
    UNKNOWN_DOMAIN(404, false),
    LLM_FORMAT(502, false),
    LLM_UNAVAILABLE(503, true),
    LLM_TIMEOUT(504, false);

    private final int status;
    private final boolean retryable;

    EngineErrorCode(int status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public int status() { return status; }

    public boolean retryable() { return retryable; }
}
