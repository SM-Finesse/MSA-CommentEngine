package com.finesse.api.engine.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** high · normal · low. 'none'처럼 모르는 값은 null로 읽고 검증에서 거절한다 */
public enum Zone {
    HIGH("high"), NORMAL("normal"), LOW("low");

    private final String json;

    Zone(String json) { this.json = json; }

    @JsonValue
    public String json() { return json; }

    @JsonCreator
    public static Zone from(String value) {
        for (Zone z : values()) {
            if (z.json.equals(value)) {
                return z;
            }
        }
        return null;
    }
}
