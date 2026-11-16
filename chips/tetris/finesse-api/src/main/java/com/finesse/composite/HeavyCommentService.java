package com.finesse.composite;

import com.finesse.api.engine.CommentEngineService;
import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 화면의 heavy 코멘트(SSE) — 모놀리식 {@code CommentService.getHeavyStream}(정한비)의 흐름을 옮긴 것.
 * LLM 서버를 직접 부르던 자리를 엔진 계약의 section(comment-engine, domain "tetris")으로 바꿨다.
 * 화면이 받는 이벤트는 모놀리식과 같다 (API 명세서 4.2-1절).
 * <pre>
 * event: start    data: {"total_chapters": 8}                              스트림을 열자마자
 * event: chapter  id: {chapter_id}  data: {"chapter_id", "status", "footnote"}   챕터가 끝나는 대로, 챕터당 정확히 1건
 * : ping                                                                    10초마다 (이벤트 아님)
 * event: done     data: {"completed", "failed_chapters", "meta": {"elapsed_ms"}} 마지막. 화면은 이걸 받고 연결을 닫는다
 * event: error    data: {"error_code", "message", "retry_after_seconds"}        챕터 시작 전 stats 실패. 이 뒤 종료
 * </pre>
 * <ol>
 *   <li>stats를 먼저 받는다 (tetris-analytics). 실패하면 error 이벤트 하나 보내고 닫는다</li>
 *   <li>콜드스타트면 엔진을 부르지 않고 8챕터를 failed로</li>
 *   <li>캐시에 있는 챕터와, 상대가 없을 때의 라이벌 챕터(고정 문구)는 바로 보낸다</li>
 *   <li>나머지 챕터는 엔진 section을 부르고, 끝나는 대로 보낸다. 엔진이 에러로 답하면 그 챕터만 failed(시간 초과면 timeout)</li>
 *   <li>전체 마감(stats가 끝난 뒤부터 heavy-seconds)까지 못 끝난 챕터는 timeout으로 채우고 done</li>
 * </ol>
 * 캐시 (모놀리식과 같은 규칙): 10분, 성공(ok)한 챕터만 챕터 단위로. 생성 서버가 스스로 대체 문장을 쓴 챕터(fallback)는
 * 남기지 않는다 (rivals는 처음부터 서버 문장을 쓰는 챕터라 예외). 열쇠는 light처럼 유저 + stats의 {@code updated_at}.
 * <p>
 * 모놀리식과 다른 점
 * <ul>
 *   <li>{@code attempt_count}: 엔진이 다시 시도한 횟수를 알려 주지 않아, 엔진을 거친 챕터에는 없다 (light와 같음)</li>
 *   <li>전체 마감: 모놀리식은 LLM 서버 대수를 보고 60초/120초를 골랐다. 여기서는 생성 서버 대수를 엔진만 알므로
 *       칩 설정({@code app.endpoint.heavy-seconds})으로 정한다</li>
 *   <li>동시 호출 수: 모놀리식은 LLM 서버마다 한 번에 하나씩 보냈다(서버별 대기열). 그 대기열은 엔진에 없어서,
 *       여기서 한 번에 부르는 section 수를 {@code app.heavy.parallel-sections}로 묶는다 (기본 2 = 생성 서버 2대)</li>
 *   <li>틀린 유저명: 모놀리식은 스트림을 열기 전에 400을 줬다. 유저명 검사는 tetris-analytics에 있어서, 여기서는
 *       스트림을 연 뒤 error 이벤트(INVALID_USERNAME)로 간다</li>
 * </ul>
 */
@Service
public class HeavyCommentService {

    private static final Logger log = LoggerFactory.getLogger(HeavyCommentService.class);

    static final String DOMAIN = LightCommentService.DOMAIN;
    static final String STATUS_OK = "ok";
    static final String STATUS_FAILED = "failed";
    static final String STATUS_TIMEOUT = "timeout";
    /** 모놀리식 CommentService.NO_RIVALS_FOOTNOTE와 같은 문장 */
    static final String NO_RIVALS_FOOTNOTE = "5경기 이상 만난 상대가 아직 없어 상성을 판단하기 이릅니다.";
    static final String RIVALS = "rivals";

    /** 모놀리식 GlobalExceptionHandler와 같은 문장 */
    static final String TETRIO_UNAVAILABLE_MESSAGE = "일시적으로 조회할 수 없습니다, 잠시 후 다시 시도";

    private static final long HEARTBEAT_SECONDS = 10;
    /** SseEmitter 자체 타임아웃. 실제 마감은 heavy-seconds가 맡고, 이 값은 연결이 먼저 끊기지만 않으면 된다 */
    private static final long EMITTER_TIMEOUT_MS = 150_000L;
    /** 엔진 계약의 deadline_ms 범위 */
    private static final int MIN_DEADLINE_MS = 1_000;
    private static final int MAX_DEADLINE_MS = 120_000;

    /** chapter 이벤트 data */
    public record Chapter(String chapterId, String status, String footnote, Integer attemptCount) {
    }

    /** 챕터 결과 + 캐시 판단에만 쓰는 내부 표시(생성 서버가 대체 문장을 썼는지). 응답에는 chapter만 나간다 */
    record Result(Chapter chapter, boolean fallback) {
        static Result of(String id, String status, String footnote, Integer attempts) {
            return new Result(new Chapter(id, status, footnote, attempts), false);
        }

        boolean ok() {
            return STATUS_OK.equals(chapter.status());
        }
    }

    /** start 이벤트 data */
    public record Start(int totalChapters) {
    }

    /** done 이벤트 data */
    public record Done(int completed, List<String> failedChapters, Meta meta) {
        public record Meta(long elapsedMs) {
        }
    }

    /** error 이벤트 data — 모놀리식 ApiErrorResponse와 같은 모양 */
    public record StreamError(String errorCode, String message, Integer retryAfterSeconds) {
    }

    private final AnalyticsHttp analytics;
    private final CommentEngineService engine;
    private final long heavySeconds;
    private final Semaphore sectionSlots;
    private final Cache<String, Map<String, Result>> cache;
    private final ExecutorService workers;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final JsonMapper mapper = JsonMapper.builder().build();

    public HeavyCommentService(AnalyticsHttp analytics, CommentEngineService engine,
                               @Value("${app.endpoint.heavy-seconds}") long heavySeconds,
                               @Value("${app.heavy.parallel-sections:2}") int parallelSections,
                               @Value("${app.heavy-cache.ttl:10m}") Duration ttl,
                               @Value("${app.heavy-cache.max-size:1000}") long maxSize) {
        this.analytics = analytics;
        this.engine = engine;
        this.heavySeconds = heavySeconds;
        this.sectionSlots = new Semaphore(Math.max(1, parallelSections), true);
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build();
        // 추적이 끊기지 않게 부른 스레드의 문맥(현재 추적)을 일하는 스레드로 넘긴다 (light와 같음)
        ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();
        this.workers = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), snapshots::captureAll);
    }

    /** @param rawUsername 화면이 보낸 그대로(인코딩된) 유저명 — tetris-analytics에 그대로 넘긴다 */
    public SseEmitter stream(String rawUsername) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        // 스트림을 열자마자 보낸다 — stats 수집(최대 20초)을 기다리는 동안 화면이 "첫 이벤트 없음"으로 끊지 않게 (QA R-05)
        try {
            emitter.send(SseEmitter.event().name("start").data(new Start(HeavyChapterData.CHAPTER_IDS.size())));
        } catch (IOException e) {
            // 아직 응답 전이라 실패할 일은 거의 없다 — 실패해도 챕터 전송은 그대로 진행
        }
        workers.execute(() -> run(rawUsername, emitter));
        return emitter;
    }

    private void run(String rawUsername, SseEmitter emitter) {
        JsonNode stats;
        try {
            AnalyticsHttp.Relayed relayed = analytics.get("/api/v1/stats/" + rawUsername);
            if (relayed.status() != 200) {
                sendError(emitter, statsError(relayed));
                return;
            }
            stats = mapper.readTree(relayed.body());
        } catch (AnalyticsUnreachableException | AnalyticsTimeoutException | CallNotPermittedException e) {
            log.warn("heavy: stats를 받지 못함: {}", e.getMessage());
            sendError(emitter, new StreamError("TETRIO_API_UNAVAILABLE", TETRIO_UNAVAILABLE_MESSAGE, null));
            return;
        } catch (RuntimeException e) {
            log.warn("heavy: stats 처리 실패", e);
            sendError(emitter, new StreamError("TETRIO_API_UNAVAILABLE", TETRIO_UNAVAILABLE_MESSAGE, null));
            return;
        }

        String key = stats.path("username").asString() + "@" + stats.path("updated_at").asString();
        Map<String, Result> cachedOk = cache.getIfPresent(key);
        if (cachedOk == null) {
            cachedOk = Map.of();
        }
        if (cachedOk.size() == HeavyChapterData.CHAPTER_IDS.size()) {
            streamAndComplete(emitter, HeavyChapterData.CHAPTER_IDS.stream().map(cachedOk::get).toList());
            return;
        }
        if (stats.path("cold_start").asBoolean()) {
            // 콜드스타트는 엔진을 부르지 않는다 — 8챕터 모두 failed (모놀리식과 같음)
            streamAndComplete(emitter, HeavyChapterData.CHAPTER_IDS.stream()
                    .map(id -> Result.of(id, STATUS_FAILED, null, 0)).toList());
            return;
        }
        computeStreaming(key, emitter, cachedOk, stats);
    }

    /**
     * stats 단계 실패를 error 이벤트 본문으로 — 모놀리식 statsFailureBody와 같은 구분:
     * USER_NOT_FOUND, SERVER_BUSY(+retry_after_seconds), 그 밖은 TETRIO_API_UNAVAILABLE.
     * 틀린 유저명(INVALID_USERNAME)은 tetris-analytics가 준 코드·문장 그대로.
     */
    StreamError statsError(AnalyticsHttp.Relayed relayed) {
        JsonNode body;
        try {
            body = mapper.readTree(relayed.body());
        } catch (RuntimeException e) {
            body = mapper.createObjectNode();
        }
        String code = body.path("error_code").isString() ? body.path("error_code").asString() : "";
        String message = body.path("message").isString() ? body.path("message").asString() : null;
        return switch (code) {
            case "USER_NOT_FOUND", "INVALID_USERNAME" -> new StreamError(code, message, null);
            case "SERVER_BUSY" -> new StreamError(code, message, retryAfter(relayed, body));
            default -> new StreamError("TETRIO_API_UNAVAILABLE", TETRIO_UNAVAILABLE_MESSAGE, null);
        };
    }

    /** SSE에는 Retry-After 헤더를 실을 수 없어 본문 칸으로 보낸다 */
    private static Integer retryAfter(AnalyticsHttp.Relayed relayed, JsonNode body) {
        if (body.path("retry_after_seconds").isNumber()) {
            return body.path("retry_after_seconds").asInt();
        }
        try {
            return relayed.retryAfter() == null ? null : Integer.valueOf(relayed.retryAfter().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sendError(SseEmitter emitter, StreamError body) {
        log.warn("heavy 스트림 시작 전 stats 실패({})", body.errorCode());
        try {
            emitter.send(SseEmitter.event().name("error").data(body));
        } catch (IOException | IllegalStateException ignored) {
            // 이미 끊긴 연결
        }
        emitter.complete();
    }

    private void streamAndComplete(SseEmitter emitter, List<Result> results) {
        for (Result r : results) {
            if (!sendChapter(emitter, r)) {
                return;
            }
        }
        sendDone(emitter, results, 0);
        emitter.complete();
    }

    private boolean sendChapter(SseEmitter emitter, Result result) {
        try {
            emitter.send(SseEmitter.event().id(result.chapter().chapterId()).name("chapter").data(result.chapter()));
            return true;
        } catch (IOException | IllegalStateException e) {
            // 화면이 이미 연결을 끊음 — 더 보내지 않는다 (이미 보낸 엔진 호출은 계속 진행해서 캐시에는 반영)
            return false;
        }
    }

    /** 8개를 다 못 채웠어도 항상 보낸다 — 화면은 이걸 받고 스스로 연결을 닫는다 (안 닫으면 브라우저가 다시 연결) */
    private void sendDone(SseEmitter emitter, List<Result> results, long elapsedMs) {
        List<String> failed = results.stream().filter(r -> !r.ok()).map(r -> r.chapter().chapterId()).toList();
        try {
            emitter.send(SseEmitter.event().name("done")
                    .data(new Done(results.size() - failed.size(), failed, new Done.Meta(elapsedMs))));
        } catch (IOException | IllegalStateException e) {
            // 이미 끊긴 연결
        }
    }

    private void computeStreaming(String key, SseEmitter emitter, Map<String, Result> cachedOk, JsonNode stats) {
        long startedAt = System.currentTimeMillis();
        long deadlineNanos = System.nanoTime() + heavySeconds * 1_000_000_000L;

        Map<String, Map<String, Object>> chapterData = HeavyChapterData.from(stats);
        Map<String, Result> collected = new ConcurrentHashMap<>();
        AtomicBoolean clientGone = new AtomicBoolean(false);
        // 챕터 전송과 마감(timeout 채우기 + done)을 한 락으로 묶는다 — 챕터가 done 뒤에 나가는 일이 없게
        Object streamLock = new Object();
        emitter.onError(e -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));

        // 캐시에 남아 있던 성공 챕터와, 상대가 없을 때의 라이벌 챕터는 엔진을 부르지 않고 바로 보낸다
        synchronized (streamLock) {
            for (String id : HeavyChapterData.CHAPTER_IDS) {
                Result hit = cachedOk.get(id);
                if (hit == null && RIVALS.equals(id) && HeavyChapterData.hasNoRivals(stats)) {
                    // 정상적인 빈 상태라 failed로 보내지 않는다 (failed면 화면에 오류처럼 보인다)
                    hit = Result.of(id, STATUS_OK, NO_RIVALS_FOOTNOTE, 0);
                }
                if (hit != null) {
                    collected.put(id, hit);
                    if (!clientGone.get() && !sendChapter(emitter, hit)) {
                        clientGone.set(true);
                    }
                }
            }
        }

        // 챕터가 늦게 올 때 프록시 · 브라우저가 유휴 스트림을 끊지 않게 주석 줄(": ping")을 보낸다
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            synchronized (streamLock) {
                if (clientGone.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    clientGone.set(true);
                }
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        List<CompletableFuture<Void>> allDone = new ArrayList<>();
        for (String id : HeavyChapterData.CHAPTER_IDS) {
            if (collected.containsKey(id)) {
                continue;
            }
            Map<String, Object> data = chapterData.get(id);
            allDone.add(CompletableFuture
                    .supplyAsync(() -> callSection(id, data, deadlineNanos), workers)
                    .thenAccept(result -> {
                        synchronized (streamLock) {
                            // 마감 뒤 timeout으로 이미 채워진 챕터면 버린다 — 챕터당 정확히 1건만
                            if (collected.putIfAbsent(id, result) != null) {
                                return;
                            }
                            if (!clientGone.get() && !sendChapter(emitter, result)) {
                                clientGone.set(true);
                            }
                        }
                    }));
        }

        // 마감까지 스레드를 붙잡고 기다리지 않는다. 전체 마감이 되면 completeOnTimeout이 마무리를 깨운다
        CompletableFuture.allOf(allDone.toArray(CompletableFuture<?>[]::new))
                .completeOnTimeout(null, heavySeconds, TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> {
                    heartbeat.cancel(false);
                    long elapsedMs = System.currentTimeMillis() - startedAt;
                    finish(key, emitter, collected, clientGone, streamLock, elapsedMs, cachedOk.size());
                });
    }

    /** 엔진 section 한 번. 절대 예외를 던지지 않는다 — 실패는 그 챕터의 status로 */
    private Result callSection(String id, Map<String, Object> data, long deadlineNanos) {
        boolean acquired = false;
        try {
            long waitNanos = deadlineNanos - System.nanoTime();
            acquired = waitNanos > 0 && sectionSlots.tryAcquire(waitNanos, TimeUnit.NANOSECONDS);
            if (!acquired) {
                return Result.of(id, STATUS_TIMEOUT, null, null);
            }
            long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000;
            if (remainingMs < MIN_DEADLINE_MS) {
                return Result.of(id, STATUS_TIMEOUT, null, null);
            }
            SectionCommentResult r = engine.section(new SectionCommentRequest(DOMAIN, id, data,
                    (int) Math.min(remainingMs, MAX_DEADLINE_MS)));
            if (r == null || r.text() == null || r.text().isBlank()) {
                return Result.of(id, STATUS_FAILED, null, null);
            }
            return new Result(new Chapter(id, STATUS_OK, r.text(), null), r.fallback());
        } catch (EngineException e) {
            log.warn("heavy: {} 챕터 실패 {}: {}", id, e.code(), e.getMessage());
            return Result.of(id, e.code() == EngineErrorCode.LLM_TIMEOUT ? STATUS_TIMEOUT : STATUS_FAILED, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.of(id, STATUS_FAILED, null, null);
        } catch (RuntimeException e) {
            log.warn("heavy: {} 챕터 실패", id, e);
            return Result.of(id, STATUS_FAILED, null, null);
        } finally {
            if (acquired) {
                sectionSlots.release();
            }
        }
    }

    private void finish(String key, SseEmitter emitter, Map<String, Result> collected, AtomicBoolean clientGone,
                        Object streamLock, long elapsedMs, int reused) {
        // 마감까지 못 끝난 챕터는 timeout으로 채운다 — done 전에 항상 정확히 8건이 간다 (화면이 이 전제로 짜여 있음)
        List<Result> finalResults = new ArrayList<>();
        synchronized (streamLock) {
            for (String id : HeavyChapterData.CHAPTER_IDS) {
                Result timeout = Result.of(id, STATUS_TIMEOUT, null, null);
                Result existing = collected.putIfAbsent(id, timeout);
                if (existing == null && !clientGone.get()) {
                    sendChapter(emitter, timeout);
                }
                finalResults.add(existing != null ? existing : timeout);
            }
            if (!clientGone.get()) {
                sendDone(emitter, finalResults, elapsedMs);
            }
        }

        Map<String, Result> ok = new LinkedHashMap<>();
        for (Result r : finalResults) {
            if (r.ok() && !isGeneratedFallback(r)) {
                ok.put(r.chapter().chapterId(), r);
            }
        }
        long fallbacks = finalResults.stream().filter(HeavyCommentService::isGeneratedFallback).count();
        log.info("heavy 코멘트 처리 종료: {}ms (한도 {}s, 캐시 재사용 {}챕터, 성공 {}챕터, 대체 문장 {}챕터)",
                elapsedMs, heavySeconds, reused, finalResults.stream().filter(Result::ok).count(), fallbacks);
        if (!ok.isEmpty()) {
            cache.put(key, Map.copyOf(ok));
        }
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 이미 닫힌 스트림
        }
    }

    /**
     * 생성 서버가 대체 문장을 보냈는지. rivals는 처음부터 서버 문장을 쓰는 챕터라 대체 문장으로 치지 않는다 —
     * 다시 불러도 같은 문장이므로 캐시해도 된다 (모놀리식 isLlmFallback과 같음).
     */
    static boolean isGeneratedFallback(Result r) {
        return r.fallback() && !RIVALS.equals(r.chapter().chapterId());
    }
}
