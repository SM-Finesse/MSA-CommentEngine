package com.finesse.composite;

/**
 * 엔진 자체에 닿지 못함 (연결 거부, 이름을 못 찾음, 연결·응답 시간 초과).
 * 다시 시도하는 것도, 회로 차단기가 실패로 세는 것도 이것뿐이다 (application.yml resilience4j.*.instances.engine).
 * 엔진이 에러로 <b>답한</b> 것은 엔진이 살아 있다는 뜻이라 여기에 들어가지 않는다.
 */
public class EngineUnreachableException extends RuntimeException {

    public EngineUnreachableException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
