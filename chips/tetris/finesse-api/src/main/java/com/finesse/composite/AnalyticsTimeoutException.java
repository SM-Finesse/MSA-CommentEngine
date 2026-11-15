package com.finesse.composite;

/**
 * tetris-analytics가 요청을 받고 읽기 타임아웃(analytics.read-timeout-ms) 안에 답하지 않음.
 * tetris-analytics는 자기 마감(stats 20초)에 502로 답하게 되어 있으므로 이건 멈춰 있다는 뜻이다.
 * 회로 차단기는 실패로 세지만, 다시 부르면 화면이 그만큼 더 기다리므로 다시 시도하지 않는다.
 */
public class AnalyticsTimeoutException extends RuntimeException {

    public AnalyticsTimeoutException(Throwable cause) {
        super("tetris-analytics did not answer in time: " + cause.getMessage(), cause);
    }
}
