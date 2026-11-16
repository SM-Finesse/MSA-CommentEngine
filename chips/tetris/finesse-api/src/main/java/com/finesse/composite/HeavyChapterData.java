package com.finesse.composite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * stats 응답에서 heavy 챕터별 생성 서버 입력(data)을 만든다 — 모놀리식 {@code CommentService.buildChapterData}(정한비)를
 * stats JSON 위에서 그대로 옮긴 것 (LLM/AI 파트 설계 v1.2 5.3절 · 7.3절 · 7.4절, 10/7 백엔드 결정).
 * <ul>
 *   <li>챕터마다 data는 object. 값이 없는 칸은 키째 뺀다</li>
 *   <li>attack · defense는 Δ 두 개만 (my_avg · opp_avg는 화면 막대용이라 넣지 않는다 — llm-server가 모르는 키를 거절)</li>
 *   <li>comeback_rate에는 두 비율과 표본 수 4개</li>
 *   <li>rivals는 앞에서 5명만, last_match_at 없이</li>
 * </ul>
 */
final class HeavyChapterData {

    /** 챕터 순서 — 모놀리식 HEAVY_CHAPTER_IDS와 같다. tetris 팩의 sections와도 같아야 한다 */
    static final List<String> CHAPTER_IDS = List.of(
            "tr_trend", "playstyle", "attack", "defense",
            "strength_split", "comeback_rate", "session_vs_slope", "rivals");

    /** 라이벌 챕터에 넣는 인원 (v1.2 7.4절, 백엔드 결정 10/7) */
    static final int RIVALS_FOR_LLM = 5;

    private HeavyChapterData() {
    }

    static Map<String, Map<String, Object>> from(JsonNode stats) {
        JsonNode d = stats.path("delta_metrics");
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        map.put("tr_trend", pick(d, "tr_trend_delta"));
        map.put("playstyle", all(d.path("playstyle_relative")));
        map.put("attack", pick(d.path("attack"), "delta_app", "delta_weighted_app"));
        map.put("defense", pick(d.path("defense"), "delta_vs_apm", "delta_cheese_index"));
        map.put("strength_split", pick(d, "strength_split"));
        Map<String, Object> comeback = pick(d, "comeback_rate", "comeback_rate_against");
        comeback.putAll(pick(d.path("comeback_samples"), "comeback_opportunities", "comeback_won",
                "comeback_against_opportunities", "comeback_against_allowed"));
        map.put("comeback_rate", comeback);
        map.put("session_vs_slope", pick(d, "session_vs_slope"));

        List<Map<String, Object>> rivals = new ArrayList<>();
        JsonNode items = stats.path("rivals").path("items");
        for (int i = 0; i < items.size() && rivals.size() < RIVALS_FOR_LLM; i++) {
            JsonNode r = items.get(i);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("nickname_masked", r.path("nickname_masked").isString() ? r.path("nickname_masked").asString() : null);
            one.put("matches", r.path("matches").asInt());
            one.put("wins", r.path("wins").asInt());
            one.put("losses", r.path("losses").asInt());
            rivals.add(one);
        }
        Map<String, Object> rivalsData = new LinkedHashMap<>();
        rivalsData.put("rivals", rivals);
        map.put("rivals", rivalsData);
        return map;
    }

    /** 반복 조우(5경기 이상) 상대가 없으면 라이벌 챕터는 생성 서버 없이 고정 문구를 ok로 보낸다 */
    static boolean hasNoRivals(JsonNode stats) {
        return stats.path("rivals").path("items").isEmpty();
    }

    /** 이름을 댄 칸 중 숫자인 것만 */
    private static Map<String, Object> pick(JsonNode node, String... names) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String name : names) {
            JsonNode v = node.path(name);
            if (v.isNumber()) {
                m.put(name, v.numberValue());
            }
        }
        return m;
    }

    /** object의 숫자 칸 전부 (없으면 빈 object) */
    private static Map<String, Object> all(JsonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                JsonNode v = node.get(name);
                if (v.isNumber()) {
                    m.put(name, v.numberValue());
                }
            }
        }
        return m;
    }
}
