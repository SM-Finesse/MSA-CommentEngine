package com.finesse.engine.generation;

import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import com.finesse.api.engine.dto.Versions;
import com.finesse.api.pack.DomainPack;
import com.finesse.engine.EngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * legacy 방식 — 지금 llm-server로 <b>칸 이름만 바꿔</b> 넘기는 어댑터 (설계 방안 3장, B안).
 * <pre>
 * items[].metric · value · zone → sentence-path 의 stat · value · zone (zone은 zone-labels로 글자 변환)
 * items 전체                    → summary-path  의 { items: [...] }
 * section · data                → section-path  의 { (section-field): section, data }
 * 응답 sentence · light_summary · (section-text-field) → sentence · summary · text
 * </pre>
 * 지표 · 섹션 이름은 모른다. llm-server 쪽 경로 · 칸 이름은 설정(engine.legacy.*)에 있다.
 * <p>
 * 시간 규칙은 백엔드 LlmClient 라이트 규칙과 같다: 문장은 첫 서버, 요약은 둘째 서버로 동시에.
 * 호출 1회는 call-timeout과 남은 시간 중 짧은 쪽, 연결 실패면 남은 시간이 min-remaining 이상일 때만 다른 서버로 한 번 더.
 * (백엔드의 "실패 서버 30초 제외"는 프로토타입에서 뺐다)
 */
@Component
public class LegacyAdapter {

    private static final Logger log = LoggerFactory.getLogger(LegacyAdapter.class);

    private final EngineProperties properties;
    private final EngineProperties.Legacy cfg;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger roundRobin = new AtomicInteger();

    public LegacyAdapter(EngineProperties properties) {
        this.properties = properties;
        this.cfg = properties.legacy();
        this.http = HttpClient.newBuilder().connectTimeout(cfg.connectTimeout()).build();
    }

    public LightCommentResult light(LightCommentRequest request, DomainPack pack, long deadlineNanos) {
        List<String> servers = pickServers();
        String sentenceServer = servers.get(0);
        String summaryServer = servers.size() > 1 ? servers.get(1) : servers.get(0);
        String other = servers.size() > 1 ? servers.get(1) : null;
        String summaryOther = servers.size() > 1 ? servers.get(0) : null;

        List<CompletableFuture<Outcome>> sentenceCalls = new ArrayList<>();
        for (MetricItem item : request.items()) {
            Map<String, Object> body = legacyItem(item);
            sentenceCalls.add(CompletableFuture.supplyAsync(
                    () -> callWithFailover(sentenceServer, other, cfg.sentencePath(), body, deadlineNanos), executor));
        }
        Map<String, Object> summaryBody = Map.of("items", request.items().stream().map(this::legacyItem).toList());
        CompletableFuture<Outcome> summaryCall = CompletableFuture.supplyAsync(
                () -> callWithFailover(summaryServer, summaryOther, cfg.summaryPath(), summaryBody, deadlineNanos), executor);

        List<Outcome> all = new ArrayList<>();
        List<LightCommentResult.ItemSentence> items = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            Outcome o = sentenceCalls.get(i).join();
            all.add(o);
            String metric = request.items().get(i).metric();
            if (o instanceof Outcome.Ok ok && ok.body().get("sentence") instanceof String s && !s.isBlank()) {
                items.add(new LightCommentResult.ItemSentence(metric, s, Boolean.TRUE.equals(ok.body().get("fallback"))));
            } else {
                // llm-server조차 답하지 못한 지표 — 팩의 no_data로 채우고 fallback 표시
                items.add(new LightCommentResult.ItemSentence(metric, pack.manifest().noData(), true));
            }
        }
        Outcome s = summaryCall.join();
        all.add(s);
        String summary;
        boolean summaryFallback;
        if (s instanceof Outcome.Ok ok && ok.body().get("light_summary") instanceof String text && !text.isBlank()) {
            summary = text;
            summaryFallback = Boolean.TRUE.equals(ok.body().get("fallback"));
        } else {
            summary = pack.manifest().noData();
            summaryFallback = true;
        }
        if (all.stream().noneMatch(o -> o instanceof Outcome.Ok)) {
            throw failure(all); // 하나도 못 받았으면 에러 코드로
        }
        return new LightCommentResult(summary, items, summaryFallback, versions(pack));
    }

    public SectionCommentResult section(SectionCommentRequest request, DomainPack pack, long deadlineNanos) {
        List<String> servers = pickServers();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(cfg.sectionField(), request.section());
        body.put("data", request.data());
        Outcome o = callWithFailover(servers.get(0), servers.size() > 1 ? servers.get(1) : null,
                cfg.sectionPath(), body, deadlineNanos);
        if (o instanceof Outcome.Ok ok && ok.body().get(cfg.sectionTextField()) instanceof String text && !text.isBlank()) {
            return new SectionCommentResult(request.section(), text,
                    Boolean.TRUE.equals(ok.body().get("fallback")), versions(pack));
        }
        throw failure(List.of(o));
    }

    private Map<String, Object> legacyItem(MetricItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stat", item.metric());
        m.put("value", item.value());
        m.put("zone", cfg.zoneLabels().getOrDefault(item.zone().wire(), item.zone().wire()));
        return m;
    }

    /** 요청마다 시작 서버를 돌린다 (라운드로빈) */
    private List<String> pickServers() {
        List<String> servers = cfg.servers();
        if (servers == null || servers.isEmpty()) {
            throw new EngineException(EngineErrorCode.LLM_UNAVAILABLE, "no llm server configured", retryAfterSeconds());
        }
        int start = Math.floorMod(roundRobin.getAndIncrement(), servers.size());
        List<String> ordered = new ArrayList<>(servers.subList(start, servers.size()));
        ordered.addAll(servers.subList(0, start));
        return ordered;
    }

    private Outcome callWithFailover(String server, String other, String path, Object body, long deadlineNanos) {
        Outcome o = post(server, path, body, deadlineNanos);
        boolean canRetry = other != null
                && (o == Outcome.Fail.CONNECT || o == Outcome.Fail.UNAVAILABLE)
                && deadlineNanos - System.nanoTime() >= cfg.minRemaining().toNanos();
        if (canRetry) {
            log.info("{} 실패({}) → {}로 한 번 더", server + path, o, other);
            o = post(other, path, body, deadlineNanos);
        }
        return o;
    }

    private Outcome post(String server, String path, Object body, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return Outcome.Fail.TIMEOUT;
        }
        Duration timeout = Duration.ofNanos(Math.min(cfg.callTimeout().toNanos(), remaining));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(server + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 503) {
                return Outcome.Fail.UNAVAILABLE;
            }
            if (resp.statusCode() / 100 != 2) {
                log.warn("{} → HTTP {}", server + path, resp.statusCode());
                return Outcome.Fail.BAD_RESPONSE;
            }
            if (mapper.readValue(resp.body(), Object.class) instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) m;
                return new Outcome.Ok(map);
            }
            return Outcome.Fail.BAD_RESPONSE;
        } catch (HttpConnectTimeoutException e) {
            return Outcome.Fail.CONNECT;
        } catch (HttpTimeoutException e) {
            return Outcome.Fail.TIMEOUT;
        } catch (JacksonException e) {
            return Outcome.Fail.BAD_RESPONSE;
        } catch (IOException e) {
            return Outcome.Fail.CONNECT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.Fail.TIMEOUT;
        }
    }

    /** 모두 실패했을 때: 시간 초과가 하나라도 있으면 504, 전부 연결 문제면 503, 나머지는 502 */
    private EngineException failure(List<Outcome> outcomes) {
        if (outcomes.contains(Outcome.Fail.TIMEOUT)) {
            return new EngineException(EngineErrorCode.LLM_TIMEOUT, "llm server did not answer before deadline");
        }
        if (outcomes.stream().allMatch(o -> o == Outcome.Fail.CONNECT || o == Outcome.Fail.UNAVAILABLE)) {
            return new EngineException(EngineErrorCode.LLM_UNAVAILABLE, "llm server unavailable", retryAfterSeconds());
        }
        return new EngineException(EngineErrorCode.LLM_FORMAT, "llm server returned an unusable response");
    }

    private int retryAfterSeconds() {
        return (int) cfg.retryAfter().toSeconds();
    }

    private Versions versions(DomainPack pack) {
        return new Versions(properties.version(), pack.manifest().version());
    }

    sealed interface Outcome {
        record Ok(Map<String, Object> body) implements Outcome {
        }

        enum Fail implements Outcome { CONNECT, UNAVAILABLE, TIMEOUT, BAD_RESPONSE }
    }
}
