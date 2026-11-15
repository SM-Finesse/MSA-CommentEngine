package com.finesse.composite;

import com.finesse.api.engine.CommentEngineService;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.MetricItem;
import com.finesse.composite.dto.LightCommentResponse;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 화면의 light 코멘트 — 모놀리식 {@code CommentService.getLight}(정한비)의 흐름을 옮긴 것.
 * LLM 서버를 직접 부르던 자리를 엔진 계약(comment-engine, domain "tetris")으로 바꿨다.
 * <ol>
 *   <li>stats를 먼저 받는다 (tetris-analytics). 에러(400·404·502·503)는 stats 경로와 같은 응답 그대로</li>
 *   <li>콜드스타트면 엔진을 부르지 않고 안내 문장</li>
 *   <li>calc가 고른 하이라이트({@link LightItems})로 엔진 light를 부른다. 마감은 stats가 끝난 뒤부터 40초</li>
 *   <li>고른 지표가 없으면 엔진을 부르지 않고 하이라이트 없이 (정한비 11/10 {@code 03abe11}, 11/9 LLM 요청)</li>
 * </ol>
 * 캐시 (모놀리식과 같은 규칙): 10분. 콜드스타트가 아닌데 하이라이트가 3개 미만이거나 대체 문장이 섞였으면 남기지 않는다.
 * 모놀리식은 stats가 새로 계산될 때 light 캐시를 지웠는데, 여기서는 stats가 다른 서비스에 있으므로 열쇠에 stats의
 * {@code updated_at}을 넣어 같은 효과를 낸다 (stats가 새로 계산되면 열쇠가 바뀐다).
 */
@Service
public class LightCommentService {

    private static final Logger log = LoggerFactory.getLogger(LightCommentService.class);

    /** 이 칩(finesse-api)의 팩 */
    static final String DOMAIN = "tetris";
    /** 모놀리식 CommentService.computeLight의 콜드스타트 안내와 같은 문장 */
    static final String COLD_START_SUMMARY = "최근 매치 데이터가 부족해 하이라이트를 표시할 수 없습니다.";
    static final String NO_HIGHLIGHT_SUMMARY = LightCommentDemoController.NO_HIGHLIGHT_SUMMARY;

    /** 캐시 값 — cacheable은 응답에 넣지 않는 내부 표시 */
    record Computed(LightCommentResponse response, boolean cacheable) {
    }

    private final AnalyticsHttp analytics;
    private final CommentEngineService engine;
    private final long lightDeadlineMs;
    private final int circuitOpenSeconds;
    private final AsyncCache<String, Computed> cache;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public LightCommentService(AnalyticsHttp analytics, CommentEngineService engine,
                               @Value("${app.endpoint.light-seconds}") long lightSeconds,
                               @Value("${app.light-cache.ttl:10m}") Duration ttl,
                               @Value("${app.light-cache.max-size:1000}") long maxSize,
                               @Value("${resilience4j.circuitbreaker.instances.analytics.wait-duration-in-open-state}")
                               Duration circuitOpen) {
        this.analytics = analytics;
        this.engine = engine;
        this.lightDeadlineMs = lightSeconds * 1000;
        this.circuitOpenSeconds = (int) Math.max(1, circuitOpen.toSeconds());
        // 같은 열쇠의 동시 요청은 한 번만 만든다. 엔진 호출(최대 40초)은 가상 스레드에서.
        // 추적이 끊기지 않게 부른 스레드의 문맥(현재 추적)을 넘긴다
        ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();
        ExecutorService loaders = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(),
                snapshots::captureAll);
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).executor(loaders).buildAsync();
    }

    /** @param rawUsername 화면이 보낸 그대로(인코딩된) 유저명 — tetris-analytics에 그대로 넘긴다 */
    public ResponseEntity<?> light(String rawUsername) {
        AnalyticsHttp.Relayed stats = fetchStats(rawUsername);
        if (stats.status() != 200) {
            return relay(stats);
        }
        long statsDone = System.nanoTime();
        JsonNode s = mapper.readTree(stats.body());
        String key = s.path("username").asString() + "@" + s.path("updated_at").asString();

        CompletableFuture<Computed> future = cache.get(key, (k, executor) ->
                CompletableFuture.supplyAsync(() -> compute(s, statsDone), executor));
        Computed computed;
        try {
            computed = future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re; // EngineException 등 — ApiErrorHandler가 화면 코드로 바꾼다
            }
            throw e;
        }
        if (!computed.cacheable()) {
            // 보여 주되 남기지 않는다 — 다음 요청 때 다시 만든다 (모놀리식 getLight의 evict와 같음)
            cache.synchronous().invalidate(key);
        }
        return ResponseEntity.ok(computed.response());
    }

    private Computed compute(JsonNode stats, long statsDone) {
        if (stats.path("cold_start").asBoolean()) {
            return new Computed(new LightCommentResponse(COLD_START_SUMMARY, List.of()), true);
        }
        List<MetricItem> items = LightItems.from(stats);
        if (items.isEmpty()) {
            return new Computed(new LightCommentResponse(NO_HIGHLIGHT_SUMMARY, List.of()), false);
        }
        int remaining = (int) Math.max(1000, lightDeadlineMs - (System.nanoTime() - statsDone) / 1_000_000);
        LightCommentResult result = engine.light(new LightCommentRequest(DOMAIN, items, remaining));
        List<LightCommentResponse.Highlight> highlights = result.items().stream()
                .map(i -> new LightCommentResponse.Highlight(i.metric(), i.sentence()))
                .toList();
        boolean cacheable = !result.fallback() && highlights.size() >= LightItems.MAX;
        if (result.fallback()) {
            log.info("light: 대체 문장이 섞여 캐시하지 않음 (지표 {}개, pack {})", items.size(),
                    result.versions() == null ? null : result.versions().pack());
        }
        return new Computed(new LightCommentResponse(result.summary(), highlights), cacheable);
    }

    private AnalyticsHttp.Relayed fetchStats(String rawUsername) {
        String path = "/api/v1/stats/" + rawUsername;
        try {
            return analytics.get(path);
        } catch (AnalyticsUnreachableException | AnalyticsTimeoutException e) {
            log.warn("light: stats를 받지 못함 {}: {}", path, e.getMessage());
            throw new StatsUnavailableException(e.getMessage(), StatsRelayController.UNREACHABLE_RETRY_AFTER_SECONDS, e);
        } catch (CallNotPermittedException e) {
            throw new StatsUnavailableException("circuit to tetris-analytics is open", circuitOpenSeconds, e);
        }
    }

    /** stats 에러는 stats 경로와 같은 응답 그대로 (모놀리식 getLight도 getStats의 예외를 그대로 올림) */
    private static ResponseEntity<byte[]> relay(AnalyticsHttp.Relayed r) {
        ResponseEntity.BodyBuilder out = ResponseEntity.status(r.status());
        if (r.contentType() != null) {
            out.contentType(r.contentType());
        }
        if (r.retryAfter() != null) {
            out.header(HttpHeaders.RETRY_AFTER, r.retryAfter());
        }
        return out.body(r.body());
    }
}
