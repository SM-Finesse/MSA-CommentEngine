package com.finesse.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

/**
 * tetris 칩의 분석 서비스. 모놀리식 backend의 calc·통계 부분을 고치지 않고 옮긴 것이다
 * (원본: SM-Finesse/Finesse main f1e06d1, backend/). 애너테이션은 모놀리식 BackendApplication과 같다.
 */
@SpringBootApplication
@EnableCaching
@ConfigurationPropertiesScan
public class TetrisAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(TetrisAnalyticsApplication.class, args);
    }
}
