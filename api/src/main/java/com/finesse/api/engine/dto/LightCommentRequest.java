package com.finesse.api.engine.dto;

import com.finesse.api.engine.EngineException;
import java.util.List;

public record LightCommentRequest(String domain, List<MetricItem> items, Integer deadlineMs) {

    public static final int MAX_ITEMS = 32;

    /** 확정안 3.3절 규칙. 팩과 맞춰 보는 검사(모르는 metric)는 엔진이 한다 */
    public void validate() {
        Deadline.check(deadlineMs);
        if (domain == null || domain.isBlank()) {
            throw EngineException.invalid("domain is required");
        }
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw EngineException.invalid("items must have 1.." + MAX_ITEMS + " entries");
        }
        for (MetricItem item : items) {
            if (item == null || item.metric() == null || item.metric().isBlank()) {
                throw EngineException.invalid("items[].metric is required");
            }
            if (item.value() == null || !Double.isFinite(item.value())) {
                throw EngineException.invalid(item.metric() + ": value must be a finite number");
            }
            if (item.zone() == null) {
                throw EngineException.invalid(item.metric() + ": zone must be high | normal | low");
            }
        }
    }
}
