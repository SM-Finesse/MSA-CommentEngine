package com.finesse.api.pack;

/** 팩 검사 실패 — 엔진은 이 예외가 나면 시작하지 않는다 (fail fast) */
public class PackValidationException extends RuntimeException {

    public PackValidationException(String message) {
        super(message);
    }

    public PackValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
