package com.finesse.composite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 칩 쪽 조합 서비스 — 지금 백엔드에서 CommentService가 하는 일 중 "LLM 부르기"를 엔진 계약 호출로 바꾼 모습.
 * (stats 계산 · TETR.IO 수집은 tetris-analytics 몫이라 이 프로토타입에서는 하이라이트 판정 결과를 입력으로 받는다)
 */
@SpringBootApplication
public class FinesseApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinesseApiApplication.class, args);
    }
}
