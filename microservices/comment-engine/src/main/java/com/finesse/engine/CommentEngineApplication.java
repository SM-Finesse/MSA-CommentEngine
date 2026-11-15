package com.finesse.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 본체 — 어떤 칩(팩)이 꽂혀 있는지 모른 채로 시작해서 /chips 를 읽고 슬롯을 연다 */
@SpringBootApplication
public class CommentEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommentEngineApplication.class, args);
    }
}
