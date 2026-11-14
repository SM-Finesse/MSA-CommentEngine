package com.finesse.api.engine;

/** 에러 본문 {error_code, message}. 원래 설계에서는 util 모듈 자리 (확정안 8절) */
public record ErrorBody(String errorCode, String message) {
}
