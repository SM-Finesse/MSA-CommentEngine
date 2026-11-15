package com.finesse.msa.testsupport;

import com.sun.net.httpserver.HttpServer;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 시험 JVM이 시작될 때 WebClient(Reactor Netty + Jackson)로 로컬 HTTP 호출을 몇 번 해서 데워 둔다.
 *
 * <p>왜 필요한가: 모놀리식 backend에서는 같은 client 패키지의 LLM 쪽 WebClient 시험
 * (LightCommentClientTest, LlmClientTest)이 TetrioClientSharedLimiterTest보다 먼저 돌아서,
 * 그 시험이 시작될 때는 Netty와 Jackson 초기화가 이미 끝나 있었다. 이 모듈에는 그 시험들이 없어
 * (LLM 호출은 comment-engine과 finesse-api의 몫) 첫 WebClient 호출이 그 시험 안에서 일어나고,
 * 초기화 시간(CI에서 약 220ms)이 "다음 차례까지 남은 간격"을 깎아 마지막 단언(300ms 이상)이 깨졌다.
 *
 * <p>옮겨 온 시험 코드는 고치지 않고, 모놀리식에서 돌던 것과 같은 조건만 만들어 준다.
 * 등록은 src/test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener.
 * 데우기가 실패해도 시험은 막지 않는다.
 */
public class WebClientWarmup implements LauncherSessionListener {

    private static final AtomicBoolean DONE = new AtomicBoolean();

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        HttpServer server = null;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/warmup", exchange -> {
                byte[] body = "{\"success\":true,\"data\":{\"_id\":\"warmup\",\"xp\":1.0}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            WebClient client = WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build();
            for (int i = 0; i < 3; i++) {
                client.get().uri("/warmup").retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(10));
            }
        } catch (Exception | LinkageError e) {
            System.err.println("[WebClientWarmup] 데우기 실패, 시험은 그대로 진행: " + e);
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }
}
