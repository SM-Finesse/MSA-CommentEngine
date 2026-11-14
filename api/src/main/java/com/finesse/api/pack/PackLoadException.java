package com.finesse.api.pack;

/** 팩 검사 실패. 엔진은 이 예외가 나면 시작하지 않는다 */
public class PackLoadException extends RuntimeException {

    public PackLoadException(String message) {
        super(message);
    }
}
