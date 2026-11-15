package com.finesse.backend.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * TETR.IO 호출용 WebClient 빈. 모놀리식 {@code config/WebClientConfig}에서 TETR.IO 부분만 떼어 왔다.
 * <p>
 * 원본은 LLM 서버용 빈({@code llmWebClientBuilder})도 같은 파일에 있어서 그대로 옮기면 LLM 설정까지 따라온다.
 * LLM 호출은 MSA에서 comment-engine이 맡으므로 그 빈만 뺐다. 아래 본문은 원본과 같다.
 */
@Configuration
public class TetrioWebClientConfig {

    // records/league/recent?limit=100 응답은 라운드별 상세 데이터까지 포함해 기본 버퍼 한도(256KB)를 넘는다
    // (실측: 300판 유저의 100판짜리 응답에서 DataBufferLimitException 발생) — 10MB로 넉넉히 올려둔다.
    private static final int MAX_IN_MEMORY_SIZE = 10 * 1024 * 1024;

    // 주의: WebClient.builder()가 기본으로 쓰는 Jackson 코덱은 spring.jackson.property-naming-strategy
    // (SNAKE_CASE) 가 반영되지 않은 "맨 기본" JsonMapper를 쓴다. 그 결과 light_summary/chapter_id 같은
    // snake_case 응답 필드가 전부 null로 바인딩되는 버그가 있었다 — 앱이 실제로 쓰는(자동설정된,
    // SNAKE_CASE 적용된) JsonMapper 빈을 주입받아 인코더/디코더에 명시적으로 꽂아서 고쳤다.
    private ExchangeStrategies jacksonAwareStrategies(JsonMapper jsonMapper) {
        return ExchangeStrategies.builder()
                .codecs(c -> {
                    c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE);
                    c.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(jsonMapper));
                    c.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(jsonMapper));
                })
                .build();
    }

    @Bean
    public WebClient tetrioWebClient(JsonMapper jsonMapper, TetrioProperties tetrioProperties) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(tetrioProperties.requestTimeoutSeconds()));
        return WebClient.builder()
                .baseUrl(tetrioProperties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(jacksonAwareStrategies(jsonMapper))
                .build();
    }
}
