package com.finesse.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 생성 서버 2대 중 1대가 꺼져 있을 때 — 살아 있는 1대로 전부 처리해야 한다.
 * 11/16 12번(실제 llm-server, 13번이 꺼져 있었음)에서 heavy 챕터 하나가 "all servers excluded"로 failed가 됐다.
 * 동시에 서버를 고르는 요청끼리 차례 번호를 가로채서, 살아 있는 서버를 한 번도 보지 못하고 끝난 것이었다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OneServerDownTest {

    static final StubGenServer GEN;

    static {
        try {
            GEN = new StubGenServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 첫 번째 서버는 아무도 없는 포트(연결 거절), 두 번째만 살아 있다 */
    static final Path PACKS = TestPacks.withTetrisServers("http://127.0.0.1:1", GEN.url());

    @DynamicPropertySource
    static void packs(DynamicPropertyRegistry r) {
        r.add("engine.chips-dir", PACKS::toString);
    }

    @AfterAll
    static void stop() {
        GEN.close();
    }

    @Autowired
    Environment env;

    @Test
    void 동시에_온_섹션_요청이_모두_살아_있는_서버로_처리된다() throws Exception {
        int port = env.getRequiredProperty("local.server.port", Integer.class);
        GEN.delayMs = 20;
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // 여러 번 되풀이한다: 꺼진 서버가 제외된 상태에서 동시에 고르는 상황을 많이 만들기 위해
            for (int round = 0; round < 5; round++) {
                List<Future<HttpResponse<String>>> calls = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    calls.add(pool.submit(() -> Http.post(port, "/v1/comments/section", """
                            {"domain":"tetris","section":"attack","data":{"delta_app":0.05},"deadline_ms":50000}""")));
                }
                for (Future<HttpResponse<String>> call : calls) {
                    statuses.add(call.get().statusCode());
                }
            }
        } finally {
            GEN.delayMs = 0;
        }

        assertThat(statuses).hasSize(80).containsOnly(200);
        assertThat(GEN.maxInFlight.get()).as("살아 있는 서버가 동시에 받은 요청 수").isEqualTo(1);
    }
}
