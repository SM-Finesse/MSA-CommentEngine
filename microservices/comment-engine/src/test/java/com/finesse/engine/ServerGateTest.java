package com.finesse.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 생성 서버 한 대의 문: 한 번에 하나, 우선순위가 높은 것 먼저, 마감까지 차례가 안 오면 돌아감 */
class ServerGateTest {

    private static final Duration LONG = Duration.ofSeconds(10);

    @Test
    void 한_번에_하나만_들어간다() throws Exception {
        ServerGate gate = new ServerGate(1);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    assertThat(gate.enter(1, LONG)).isTrue();
                    max.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    Thread.sleep(20);
                    inside.decrementAndGet();
                    gate.leave();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(max.get()).isEqualTo(1);
        assertThat(gate.waitingCount()).isZero();
    }

    @Test
    void 줄에서는_우선순위가_높은_것이_먼저_같으면_온_순서대로() throws Exception {
        ServerGate gate = new ServerGate(1);
        assertThat(gate.enter(1, LONG)).isTrue(); // 문을 잡아 둔다
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(4);
        String[][] arrivals = {{"section-1", "1"}, {"section-2", "1"}, {"light-1", "0"}, {"light-2", "0"}};
        for (int i = 0; i < arrivals.length; i++) {
            String name = arrivals[i][0];
            int priority = Integer.parseInt(arrivals[i][1]);
            Thread.startVirtualThread(() -> {
                try {
                    if (gate.enter(priority, LONG)) {
                        order.add(name);
                        gate.leave();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            waitUntilWaiting(gate, i + 1); // 온 순서를 확실히 한다
        }

        gate.leave();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly("light-1", "light-2", "section-1", "section-2");
    }

    @Test
    void 마감까지_차례가_안_오면_돌아가고_줄에서도_빠진다() throws Exception {
        ServerGate gate = new ServerGate(1);
        assertThat(gate.enter(1, LONG)).isTrue();

        assertThat(gate.enter(1, Duration.ofMillis(100))).isFalse();
        assertThat(gate.waitingCount()).isZero();

        gate.leave();
        assertThat(gate.enter(1, Duration.ZERO)).as("비어 있으면 기다리지 않고 들어간다").isTrue();
    }

    @Test
    void 자리가_둘이면_둘까지_같이_들어간다() throws Exception {
        ServerGate gate = new ServerGate(2);
        assertThat(gate.enter(1, Duration.ZERO)).isTrue();
        assertThat(gate.enter(1, Duration.ZERO)).isTrue();
        assertThat(gate.enter(1, Duration.ofMillis(50))).isFalse();
        gate.leave();
        assertThat(gate.enter(1, Duration.ZERO)).isTrue();
    }

    private static void waitUntilWaiting(ServerGate gate, int count) throws InterruptedException {
        for (int i = 0; i < 500 && gate.waitingCount() < count; i++) {
            Thread.sleep(10);
        }
        assertThat(gate.waitingCount()).isEqualTo(count);
    }
}
