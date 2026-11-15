package com.finesse.composite;

import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.Zone;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * stats 응답에서 light 항목을 뽑는다 — 모놀리식 {@code CommentService.lightCommentItems}·{@code displayValue}(정한비)를
 * stats JSON 위에서 그대로 옮긴 것.
 * <ul>
 *   <li>calc가 고른 {@code highlight_judgement.picks}(0~3개)를 순서대로</li>
 *   <li>영역이 high·normal·low가 아니거나(none) 값이 없으면 뺀다 (LLM 서버가 422로 거절하므로)</li>
 *   <li>값은 화면과 같은 원값({@code delta_metrics}) — 판정에 쓴 값(picks.value)이 아니다. delta_comeback은 판정 값이 축소값이라 다르다</li>
 *   <li>같은 지표는 한 번만, 최대 3개</li>
 * </ul>
 */
final class LightItems {

    static final int MAX = 3;

    private LightItems() {
    }

    static List<MetricItem> from(JsonNode stats) {
        JsonNode picks = stats.path("highlight_judgement").path("picks");
        JsonNode delta = stats.path("delta_metrics");
        List<MetricItem> items = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) {
            JsonNode pick = picks.get(i);
            String key = text(pick.path("key"));
            Zone zone = zone(text(pick.path("zone")));
            Double value = key == null ? null : displayValue(delta, key);
            if (zone != null && value != null && Double.isFinite(value)
                    && items.stream().noneMatch(it -> it.metric().equals(key))) {
                items.add(new MetricItem(key, value, zone));
            }
        }
        return items.size() > MAX ? List.copyOf(items.subList(0, MAX)) : List.copyOf(items);
    }

    /** 화면(delta_metrics)에 보이는 원값 — 후보 11개 키 → stats 필드 (모놀리식 displayValue와 같은 표) */
    static Double displayValue(JsonNode delta, String key) {
        JsonNode node = switch (key) {
            case "delta_opener", "delta_plonk", "delta_stride", "delta_inf_ds" -> delta.path("playstyle_relative").path(key);
            case "delta_app", "delta_weighted_app" -> delta.path("attack").path(key);
            case "delta_vs_apm", "delta_cheese_index" -> delta.path("defense").path(key);
            case "strength_split", "delta_comeback", "session_vs_slope" -> delta.path(key);
            default -> null;
        };
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private static Zone zone(String zone) {
        if (zone == null) {
            return null;
        }
        return switch (zone) {
            case "high" -> Zone.HIGH;
            case "normal" -> Zone.NORMAL;
            case "low" -> Zone.LOW;
            default -> null; // none 등
        };
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
