package com.finesse.composite;

import com.finesse.api.engine.CommentEngineService;
import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.dto.DomainInfo;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/**
 * 엔진 계약을 <b>호출하는 쪽</b> 구현 — comment-engine의 컨트롤러와 같은 인터페이스를 구현한다.
 * 그래서 finesse-api 코드는 엔진이 같은 프로세스에 있든 HTTP 너머에 있든 {@link CommentEngineService}만 본다.
 * <p>
 * 마감은 처음 부를 때 정하고, 다시 시도할 때는 남은 시간만 엔진에 넘긴다 ({@link EngineHttp}).
 * <ul>
 *   <li>엔진이 에러로 답하면 기다리지 않고 바로 {@link EngineCallException}으로 — 503이면 엔진의 Retry-After(30초)를 그대로 화면에</li>
 *   <li>다시 불러도 엔진에 닿지 않거나 회로가 열려 있으면 LLM_UNAVAILABLE(503)로</li>
 * </ul>
 */
@Component
public class EngineClient implements CommentEngineService {

    /** 엔진에 닿지 않을 때 화면에 알려 줄 Retry-After(초) */
    static final int UNREACHABLE_RETRY_AFTER_SECONDS = 5;

    private final EngineHttp http;
    /** 회로가 열려 있을 때 화면에 알려 줄 Retry-After(초) = 회로가 열려 있는 시간 */
    private final int circuitOpenSeconds;

    public EngineClient(EngineHttp http,
                        @Value("${resilience4j.circuitbreaker.instances.engine.wait-duration-in-open-state}")
                        Duration circuitOpen) {
        this.http = http;
        this.circuitOpenSeconds = (int) Math.max(1, circuitOpen.toSeconds());
    }

    @Override
    public LightCommentResult light(LightCommentRequest request) {
        Instant deadline = Instant.now().plusMillis(request.deadlineMs());
        return guard(() -> http.post("/v1/comments/light", deadline,
                remaining -> new LightCommentRequest(request.domain(), request.items(), remaining),
                LightCommentResult.class));
    }

    @Override
    public SectionCommentResult section(SectionCommentRequest request) {
        Instant deadline = Instant.now().plusMillis(request.deadlineMs());
        return guard(() -> http.post("/v1/comments/section", deadline,
                remaining -> new SectionCommentRequest(request.domain(), request.section(), request.data(), remaining),
                SectionCommentResult.class));
    }

    @Override
    public List<DomainInfo> domains() {
        return guard(() -> http.get("/v1/domains", new ParameterizedTypeReference<List<DomainInfo>>() {
        }));
    }

    private <T> T guard(Supplier<T> call) {
        try {
            return call.get();
        } catch (EngineUnreachableException e) {
            throw new EngineCallException(EngineErrorCode.LLM_UNAVAILABLE,
                    "comment-engine unreachable: " + e.getMessage(), UNREACHABLE_RETRY_AFTER_SECONDS);
        } catch (CallNotPermittedException e) {
            throw new EngineCallException(EngineErrorCode.LLM_UNAVAILABLE,
                    "circuit to comment-engine is open", circuitOpenSeconds);
        }
    }
}
