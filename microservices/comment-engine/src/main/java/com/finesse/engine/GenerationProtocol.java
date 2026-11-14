package com.finesse.engine;

import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.Zone;
import com.finesse.api.generation.dto.ChapterRequest;
import com.finesse.api.generation.dto.ChapterResponse;
import com.finesse.api.generation.dto.SentenceRequest;
import com.finesse.api.generation.dto.SentenceResponse;
import com.finesse.api.generation.dto.SummaryRequest;
import com.finesse.api.generation.dto.SummaryResponse;
import com.finesse.api.pack.DomainPack;
import com.finesse.engine.ServerGenerator.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ③ 생성 서버 규약 어댑터 — 엔진 계약의 말을 동결된 llm-server의 말로 바꾸는 단 한 곳 (확정안 5절).
 *   metric → stat, section → chapter_id, footnote → text, light_summary → summary,
 *   zone high/normal/low → 높음/보통/낮음
 * llm-server 칸 이름(chapter 등)이 본체 코드에 남는 곳은 여기와 api/generation 뿐이다.
 */
@Component
public class GenerationProtocol {

    static final String SENTENCE_PATH = "/v1/light/sentence";
    static final String SUMMARY_PATH = "/v1/light/summary";
    static final String SECTION_PATH = "/v1/comment/heavy-chapter";

    /** 생성 서버가 돌려준 문장 + 생성 서버 스스로 대체 문장을 썼는지 */
    public record GenText(String text, boolean fallback) {
    }

    private final ServerGenerator server;

    public GenerationProtocol(ServerGenerator server) {
        this.server = server;
    }

    public Outcome<GenText> sentence(DomainPack pack, MetricItem item, Instant deadline) {
        Outcome<SentenceResponse> o = server.call(pack, SENTENCE_PATH, toServer(item), SentenceResponse.class,
                r -> item.metric().equals(r.stat()) && notBlank(r.sentence()), deadline);
        return o.ok() ? Outcome.ok(new GenText(o.value().sentence(), Boolean.TRUE.equals(o.value().fallback())))
                      : Outcome.fail(o.failure());
    }

    public Outcome<GenText> summary(DomainPack pack, List<MetricItem> items, Instant deadline) {
        SummaryRequest body = new SummaryRequest(items.stream().map(GenerationProtocol::toServer).toList());
        Outcome<SummaryResponse> o = server.call(pack, SUMMARY_PATH, body, SummaryResponse.class,
                r -> notBlank(r.lightSummary()), deadline);
        return o.ok() ? Outcome.ok(new GenText(o.value().lightSummary(), Boolean.TRUE.equals(o.value().fallback())))
                      : Outcome.fail(o.failure());
    }

    public Outcome<GenText> section(DomainPack pack, String section, Map<String, Object> data, Instant deadline) {
        Outcome<ChapterResponse> o = server.call(pack, SECTION_PATH, new ChapterRequest(section, data), ChapterResponse.class,
                r -> section.equals(r.chapterId()) && notBlank(r.footnote()), deadline);
        return o.ok() ? Outcome.ok(new GenText(o.value().footnote(), Boolean.TRUE.equals(o.value().fallback())))
                      : Outcome.fail(o.failure());
    }

    static SentenceRequest toServer(MetricItem item) {
        return new SentenceRequest(item.metric(), item.value(), zoneForServer(item.zone()));
    }

    static String zoneForServer(Zone zone) {
        return switch (zone) {
            case HIGH -> "높음";
            case NORMAL -> "보통";
            case LOW -> "낮음";
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
