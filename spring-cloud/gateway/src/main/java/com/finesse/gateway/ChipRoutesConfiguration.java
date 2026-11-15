package com.finesse.gateway;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 칩이 들고 온 경로를 gateway에 꽂는다.
 * <p>
 * 칩은 자기 폴더의 {@code chips/<칩>/gateway.yml} 에 {@code app.routes.<이름>} 으로 "이 경로는 내 서비스로" 를 적어 온다.
 * gateway는 그 파일들을 설정으로 함께 읽고(application.yml 의 {@code chips/*}{@code /gateway.yml}), 여기서 경로로 만든다.
 * 칩을 꽂거나 뽑을 때 gateway 코드와 config-repo/gateway.yml 은 바뀌지 않는다.
 * <p>
 * 본체 경로처럼 {@code spring.cloud.gateway...routes} 목록에 적지 않는 이유: 스프링 설정에서 목록은 파일끼리 합쳐지지 않고
 * 한 파일의 것으로 통째로 바뀐다. 이름표가 붙은 묶음(map)은 파일끼리 합쳐져서, 칩마다 파일을 따로 둘 수 있다.
 * 헬스 주소({@code app.health}, {@link HealthCheckConfiguration})도 같은 이유로 묶음이다.
 */
@Configuration
@EnableConfigurationProperties(ChipRoutesConfiguration.ChipRoutes.class)
public class ChipRoutesConfiguration {

    /** 이름 → 넘길 곳 (app.routes) */
    @ConfigurationProperties("app")
    public record ChipRoutes(Map<String, Target> routes) {
    }

    /** uri: 넘길 서비스 주소, paths: 그 서비스로 보낼 경로 무늬 (예: /api/**) */
    public record Target(String uri, List<String> paths) {
    }

    @Bean
    RouteLocator chipRoutes(RouteLocatorBuilder builder, ChipRoutes chips) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        if (chips.routes() != null) {
            chips.routes().forEach((name, target) -> {
                if (target == null || target.uri() == null || target.uri().isBlank()
                        || target.paths() == null || target.paths().isEmpty()) {
                    // 잘못 적힌 칩 경로는 조용히 넘기지 않고 gateway가 뜨지 않게 한다 (팩 검사와 같은 fail-fast)
                    throw new IllegalStateException("app.routes." + name + " 에 uri 와 paths 가 있어야 합니다");
                }
                routes.route(name, r -> r.path(target.paths().toArray(String[]::new)).uri(target.uri()));
            });
        }
        return routes.build();
    }
}
