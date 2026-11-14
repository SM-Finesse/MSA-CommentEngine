package com.finesse.composite;

import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;

/**
 * 엔진을 부르다가 받은 에러. 슬롯의 {@link EngineException}에 Retry-After(초)만 덧붙인 것이다.
 * 슬롯(api 모듈)을 바꾸지 않으려고 칩 쪽에 둔다.
 */
public class EngineCallException extends EngineException {

    private final Integer retryAfterSeconds;

    public EngineCallException(EngineErrorCode code, String message, Integer retryAfterSeconds) {
        super(code, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 엔진 예외에서 Retry-After를 꺼낸다. 없으면 null */
    static Integer retryAfterOf(EngineException e) {
        return e instanceof EngineCallException c ? c.retryAfterSeconds() : null;
    }
}
