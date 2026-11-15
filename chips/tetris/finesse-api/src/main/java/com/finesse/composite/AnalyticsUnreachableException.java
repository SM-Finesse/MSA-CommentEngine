package com.finesse.composite;

/**
 * tetris-analytics에 닿지 않음 (연결 거부, 연결 타임아웃, 응답 도중 끊김).
 * 다시 시도(1번)와 회로 차단기가 세는 실패다. GET이라 다시 불러도 안전하다.
 */
public class AnalyticsUnreachableException extends RuntimeException {

    public AnalyticsUnreachableException(Throwable cause) {
        super("tetris-analytics unreachable: " + cause.getMessage(), cause);
    }
}
