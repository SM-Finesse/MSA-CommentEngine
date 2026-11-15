package com.finesse.composite;

import com.finesse.api.engine.CommentEngineService;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.Zone;
import com.finesse.composite.dto.DemoLightRequest;
import com.finesse.composite.dto.LightCommentResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 지금 백엔드 {@code GET /api/v1/comment/{username}?scope=light}의 뒷부분만 떼어 낸 데모.
 * <ol>
 *   <li>calc 하이라이트 판정 결과(stat · value · zone)를 받는다 — 실제로는 stats 캐시에서 꺼냄</li>
 *   <li>zone "none"은 버리고, 남은 게 없으면 LLM 없이 하이라이트 없는 응답 (백엔드 11/9 동작과 같음)</li>
 *   <li>엔진 계약으로 부르면서 <b>light 마감 40초 중 남은 시간</b>을 deadline_ms로 넘긴다</li>
 *   <li>엔진 응답을 지금 프론트가 받는 모양(light_summary · highlights[stat, sentence])으로 바꾼다</li>
 * </ol>
 */
@RestController
public class LightCommentDemoController {

    /** 백엔드 CommentService.NO_HIGHLIGHT_SUMMARY와 같은 문장 */
    static final String NO_HIGHLIGHT_SUMMARY = "이번 전적에서는 AI 코멘트로 짚을 지표를 고르지 못했습니다.";

    private final CommentEngineService engine;
    private final long lightDeadlineMs;

    public LightCommentDemoController(CommentEngineService engine,
                                      @Value("${app.endpoint.light-seconds}") long lightSeconds) {
        this.engine = engine;
        this.lightDeadlineMs = lightSeconds * 1000;
    }

    @PostMapping(value = "/api/v1/comment-demo/light",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public LightCommentResponse light(@Valid @RequestBody DemoLightRequest request) {
        long start = System.nanoTime();

        List<MetricItem> items = request.highlights().stream()
                .filter(h -> !"none".equals(h.zone()))
                .map(h -> new MetricItem(h.stat(), h.value(), Zone.valueOf(h.zone().toUpperCase())))
                .toList();
        if (items.isEmpty()) {
            return new LightCommentResponse(NO_HIGHLIGHT_SUMMARY, List.of());
        }

        int remaining = (int) Math.max(1000, lightDeadlineMs - (System.nanoTime() - start) / 1_000_000);
        LightCommentResult result = engine.light(new LightCommentRequest(request.domain(), items, remaining));

        return new LightCommentResponse(result.summary(), result.items().stream()
                .map(i -> new LightCommentResponse.Highlight(i.metric(), i.sentence()))
                .toList());
    }

    /** 엔진에 꽂힌 팩 확인용 */
    @GetMapping("/api/v1/comment-demo/domains")
    public Object domains() {
        return engine.domains();
    }
}
