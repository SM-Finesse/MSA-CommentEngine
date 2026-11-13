package com.finesse.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 본체 — 분야를 모르는 코멘트 엔진. 분야 지식은 전부 /packs(domain-packs) 아래 팩에서 읽는다 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CommentEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommentEngineApplication.class, args);
    }
}
