package com.finesse.api.engine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 값이 보통보다 높은지 · 보통인지 · 낮은지. <b>부르는 쪽이 계산해서 보낸다</b> — 엔진은 믿고 문장만 고른다.
 * "none"(판정 불가)은 계약에 없다. 부르는 쪽이 그런 지표는 아예 보내지 않는다.
 */
public enum Zone {
    @JsonProperty("high") HIGH,
    @JsonProperty("normal") NORMAL,
    @JsonProperty("low") LOW;

    /** JSON에 쓰는 이름 */
    public String wire() {
        return name().toLowerCase();
    }
}
