package com.finesse.api.engine;

/**
 * 엔진이 던지고(→ HTTP 에러 응답), finesse-api의 클라이언트가 에러 응답을 다시 이 예외로 되돌린다.
 * 양쪽이 같은 예외 타입을 쓰므로 에러 코드도 계약의 일부가 된다.
 */
public class EngineException extends RuntimeException {

    private final EngineErrorCode code;
    private final Integer retryAfterSeconds;

    public EngineException(EngineErrorCode code, String message) {
        this(code, message, null);
    }

    public EngineException(EngineErrorCode code, String message, Integer retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public EngineErrorCode code() {
        return code;
    }

    /** LLM_UNAVAILABLE일 때만 값이 있다 */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
