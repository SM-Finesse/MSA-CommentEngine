package com.finesse.api.engine.dto;

import com.finesse.api.engine.EngineException;

/** deadline_ms = 부르는 쪽 마감까지 남은 시간. 1,000 ~ 120,000 */
public final class Deadline {

    public static final int MIN_MS = 1_000;
    public static final int MAX_MS = 120_000;

    private Deadline() {
    }

    static void check(Integer deadlineMs) {
        if (deadlineMs == null || deadlineMs < MIN_MS || deadlineMs > MAX_MS) {
            throw EngineException.invalid("deadline_ms must be " + MIN_MS + ".." + MAX_MS);
        }
    }
}
