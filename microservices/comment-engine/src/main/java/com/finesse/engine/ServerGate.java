package com.finesse.engine;

import java.time.Duration;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 생성 서버 한 대의 문 — 한 번에 정해진 수(기본 1)만 들여보내고 나머지는 줄을 세운다.
 * 지금 서비스(모놀리식 LlmClient)의 서버별 대기열과 같은 규칙이다.
 * <ul>
 *   <li>우선순위가 높은(숫자가 작은) 요청이 먼저. 같은 우선순위끼리는 온 순서대로</li>
 *   <li>줄에서 기다린 시간도 요청의 마감에 들어간다. 마감까지 차례가 오지 않으면 들어가지 않고 돌아간다</li>
 * </ul>
 * 왜 필요한가: 생성 서버(작은 GPU 한 장)는 요청을 하나씩 처리한다. 동시에 여러 개를 보내면 뒤의 요청은 서버 안에서
 * 기다리다 1회 호출 타임아웃에 걸린다. 여기서 줄을 세우면 타임아웃은 실제로 보낸 때부터 센다.
 */
final class ServerGate {

    private static final class Waiter {
        final int priority;
        final long order;
        final Condition turn;
        boolean granted;

        Waiter(int priority, long order, Condition turn) {
            this.priority = priority;
            this.order = order;
            this.turn = turn;
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final PriorityQueue<Waiter> waiting = new PriorityQueue<>(
            Comparator.<Waiter>comparingInt(w -> w.priority).thenComparingLong(w -> w.order));
    private final int permits;
    private int running;
    private long nextOrder;

    ServerGate(int permits) {
        this.permits = Math.max(1, permits);
    }

    /**
     * 들어간다. 자리가 없으면 maxWait까지 줄에서 기다린다.
     *
     * @return 들어갔으면 true (끝나면 반드시 {@link #leave()}), 차례가 오지 않았으면 false
     */
    boolean enter(int priority, Duration maxWait) throws InterruptedException {
        lock.lock();
        try {
            if (running < permits && waiting.isEmpty()) {
                running++;
                return true;
            }
            Waiter me = new Waiter(priority, nextOrder++, lock.newCondition());
            waiting.add(me);
            long nanos = maxWait.toNanos();
            try {
                while (!me.granted) {
                    if (nanos <= 0) {
                        waiting.remove(me);
                        return false;
                    }
                    nanos = me.turn.awaitNanos(nanos);
                }
                return true;
            } catch (InterruptedException e) {
                if (me.granted) {
                    passOn(); // 차례를 받은 직후 끊겼다 — 받은 자리를 다음 사람에게 넘긴다
                } else {
                    waiting.remove(me);
                }
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    /** 나온다. 줄에 사람이 있으면 자리를 그대로 넘긴다 */
    void leave() {
        lock.lock();
        try {
            passOn();
        } finally {
            lock.unlock();
        }
    }

    private void passOn() {
        Waiter next = waiting.poll();
        if (next != null) {
            next.granted = true;
            next.turn.signal();
        } else {
            running--;
        }
    }

    /** 줄에서 기다리는 수 (로그 · 시험용) */
    int waitingCount() {
        lock.lock();
        try {
            return waiting.size();
        } finally {
            lock.unlock();
        }
    }
}
