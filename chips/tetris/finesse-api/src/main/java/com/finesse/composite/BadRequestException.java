package com.finesse.composite;

/** 400 BAD_REQUEST — 메시지는 화면에 그대로 나간다 ({@link ApiErrorHandler}) */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
