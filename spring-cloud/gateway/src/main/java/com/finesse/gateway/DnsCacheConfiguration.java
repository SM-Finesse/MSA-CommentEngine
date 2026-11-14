package com.finesse.gateway;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gateway가 서비스 이름(comment-engine 등)의 IP를 오래 기억하지 않게 한다.
 * <p>
 * Docker 내장 DNS는 "이 답을 600초 동안 써도 된다"(TTL)고 알려 주고, gateway의 HTTP 클라이언트(Reactor Netty)는
 * 그 시간만큼 옛 IP를 쓴다. 엔진 컨테이너가 새 IP로 다시 뜨면 최대 10분 동안 500이 난다
 * ({@code e2e/engine-ip-change.sh}로 재현). 그래서 DNS 답을 기억하는 시간의 상한을 짧게 둔다.
 */
@Configuration
public class DnsCacheConfiguration {

    @Bean
    HttpClientCustomizer shortDnsCache(@Value("${app.dns-cache-seconds:5}") long seconds) {
        return client -> client.resolver(spec -> spec.cacheMaxTimeToLive(Duration.ofSeconds(seconds)));
    }
}
