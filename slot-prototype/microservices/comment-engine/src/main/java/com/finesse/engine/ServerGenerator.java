package com.finesse.engine;

import com.finesse.api.pack.DomainPack;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * ③ 생성 서버 규약으로 칩의 생성 서버를 부른다. 지금 LlmClient 규칙을 옮긴 것 (확정안 7.2절).
 *  - 팩의 servers를 라운드로빈
 *  - 연결 실패한 서버는 exclude-seconds 동안 제외
 *  - 1회 호출 타임아웃 = min(call-timeout, 남은 시간)
 *  - 두 번째 시도부터는 남은 시간이 1회 호출 타임아웃보다 길 때만
 * 어떤 칩의 서버인지는 모른다. 주소는 팩이 알려 준다.
 */
@Component
public class ServerGenerator {

    private static final Logger log = LoggerFactory.getLogger(ServerGenerator.class);

    /** 실패 종류 — 엔진이 에러 코드를 고를 때 쓴다 */
    public enum Failure { UNAVAILABLE, TIMEOUT, ERROR, FORMAT }

    /** 성공이면 value, 실패면 failure */
    public record Outcome<T>(T value, Failure failure) {
        static <T> Outcome<T> ok(T v) { return new Outcome<>(v, null); }
        static <T> Outcome<T> fail(Failure f) { return new Outcome<>(null, f); }
        public boolean ok() { return failure == null; }
    }

    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, AtomicInteger> cursor = new ConcurrentHashMap<>();
    private final Map<String, Instant> excludedUntil = new ConcurrentHashMap<>();
    private final Duration callTimeout;
    private final Duration exclude;
    private final int maxAttempts;

    public ServerGenerator(JsonMapper json,
                           @Value("${engine.call-timeout-ms}") long callTimeoutMs,
                           @Value("${engine.exclude-seconds}") long excludeSeconds,
                           @Value("${engine.max-attempts}") int maxAttempts) {
        this.json = json;
        this.callTimeout = Duration.ofMillis(callTimeoutMs);
        this.exclude = Duration.ofSeconds(excludeSeconds);
        this.maxAttempts = maxAttempts;
    }

    /**
     * path로 body를 POST하고 type으로 읽는다. valid가 false면 형식 실패(FORMAT)로 보고 다음 서버로 재시도.
     */
    public <T> Outcome<T> call(DomainPack pack, String path, Object body, Class<T> type,
                               Predicate<T> valid, Instant deadline) {
        Failure last = Failure.UNAVAILABLE;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                return Outcome.fail(Failure.TIMEOUT);
            }
            if (attempt > 1 && remaining.compareTo(callTimeout) <= 0) {
                break; // 새 시도를 끝까지 기다릴 시간이 없음
            }
            String server = pick(pack);
            if (server == null) {
                log.warn("[{}] all servers excluded", pack.id());
                return Outcome.fail(Failure.UNAVAILABLE);
            }
            Duration timeout = remaining.compareTo(callTimeout) < 0 ? remaining : callTimeout;
            long started = System.nanoTime();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(server + path))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                long ms = (System.nanoTime() - started) / 1_000_000;
                if (res.statusCode() != 200) {
                    log.warn("[{}] {}{} -> HTTP {} ({}ms, try {})", pack.id(), server, path, res.statusCode(), ms, attempt);
                    last = Failure.ERROR;
                    continue;
                }
                T value = json.readValue(res.body(), type);
                if (value == null || !valid.test(value)) {
                    log.warn("[{}] {}{} -> bad format ({}ms, try {})", pack.id(), server, path, ms, attempt);
                    last = Failure.FORMAT;
                    continue;
                }
                log.info("[{}] {}{} -> 200 ({}ms, try {})", pack.id(), server, path, ms, attempt);
                return Outcome.ok(value);
            } catch (HttpConnectTimeoutException | ConnectException e) {
                excludedUntil.put(server, Instant.now().plus(exclude));
                log.warn("[{}] {} unreachable -> excluded {}s (try {})", pack.id(), server, exclude.toSeconds(), attempt);
                last = Failure.UNAVAILABLE;
            } catch (HttpTimeoutException e) {
                log.warn("[{}] {}{} -> timeout {}ms (try {})", pack.id(), server, path, timeout.toMillis(), attempt);
                last = Failure.TIMEOUT;
            } catch (JacksonException e) {
                log.warn("[{}] {}{} -> not JSON (try {})", pack.id(), server, path, attempt);
                last = Failure.FORMAT;
            } catch (IOException e) {
                log.warn("[{}] {}{} -> {} (try {})", pack.id(), server, path, e.toString(), attempt);
                last = Failure.UNAVAILABLE;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.fail(Failure.TIMEOUT);
            }
        }
        return Outcome.fail(last);
    }

    /** 라운드로빈. 제외 중인 서버는 건너뛴다. 전부 제외면 null */
    private String pick(DomainPack pack) {
        List<String> servers = pack.servers();
        AtomicInteger c = cursor.computeIfAbsent(pack.id(), k -> new AtomicInteger());
        Instant now = Instant.now();
        for (int i = 0; i < servers.size(); i++) {
            String s = servers.get(Math.floorMod(c.getAndIncrement(), servers.size()));
            Instant until = excludedUntil.get(s);
            if (until == null || now.isAfter(until)) {
                return s;
            }
        }
        return null;
    }
}
