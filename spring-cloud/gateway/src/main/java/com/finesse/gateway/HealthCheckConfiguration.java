package com.finesse.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.CompositeReactiveHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthContributor;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * gateway 헬스(/actuator/health)에 하위 서비스의 헬스를 함께 보여 준다 (수업 레포 HealthCheckConfiguration과 같은 방식).
 * gateway 주소 하나만 보면 본체 전체가 정상인지 알 수 있어서, 12번 감시 프로그램이 이 주소 하나만 보면 된다.
 * <p>
 * 수업과 다른 점
 * <ul>
 *   <li>부를 주소는 코드가 아니라 config-repo/gateway.yml 의 {@code app.health}에 있다. 서비스가 늘어도 코드는 그대로</li>
 *   <li>응답이 2xx인지만 보지 않고 {@code status}가 UP인지 본다. 회로가 열린 finesse-api는 200에 CIRCUIT_OPEN을 준다</li>
 *   <li>경로를 넘길 때와 같은 HTTP 클라이언트(주소를 5초까지만 기억, {@link DnsCacheConfiguration})를 쓴다.
 *       따로 만들면 엔진 IP가 바뀐 뒤 헬스만 최대 10분 동안 옛 주소를 본다</li>
 * </ul>
 * 컨테이너 헬스체크는 /actuator/health/readiness 라 여기 결과에 흔들리지 않는다.
 */
@Configuration
@EnableConfigurationProperties(HealthCheckConfiguration.Targets.class)
public class HealthCheckConfiguration {

    /** 하위 서비스 하나의 헬스를 기다리는 최대 시간 */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** 이름 → 헬스 주소 (app.health) */
    @ConfigurationProperties("app")
    public record Targets(Map<String, String> health) {
    }

    private final WebClient webClient;

    public HealthCheckConfiguration(WebClient.Builder builder, HttpClient gatewayHttpClient) {
        this.webClient = builder.clientConnector(new ReactorClientHttpConnector(gatewayHttpClient)).build();
    }

    @Bean
    ReactiveHealthContributor services(Targets targets) {
        Map<String, ReactiveHealthIndicator> registry = new LinkedHashMap<>();
        if (targets.health() != null) {
            targets.health().forEach((name, url) -> registry.put(name, () -> health(url)));
        }
        return CompositeReactiveHealthContributor.fromMap(registry);
    }

    private Mono<Health> health(String url) {
        return webClient.get().uri(url)
                .exchangeToMono(res -> res.bodyToMono(Map.class).defaultIfEmpty(Map.of()).map(body -> {
                    Object status = body.get("status");
                    if ("UP".equals(status)) {
                        return new Health.Builder().up().build();
                    }
                    return new Health.Builder().down()
                            .withDetail("status", String.valueOf(status))
                            .withDetail("http", res.statusCode().value())
                            .build();
                }))
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(new Health.Builder().down(ex).build()));
    }
}
