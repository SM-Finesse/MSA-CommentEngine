package com.finesse.composite.dto;

/** 지금 백엔드 공통 에러 응답과 같은 모양 — { "error_code", "message" } */
public record ApiErrorResponse(String errorCode, String message) {
}
