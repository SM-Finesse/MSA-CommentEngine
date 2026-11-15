package com.finesse.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 본체의 입구. 본체 경로는 config-repo/gateway.yml 에, 칩의 경로는 칩 폴더(chips/<칩>/gateway.yml)에 있다 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
