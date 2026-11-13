package com.finesse.engine;

import com.finesse.api.engine.CommentEngineService;
import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.dto.DomainInfo;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import com.finesse.api.pack.DomainPack;
import com.finesse.engine.generation.LegacyAdapter;
import com.finesse.engine.generation.TemplateGenerator;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 엔진 계약의 구현. 경로 · 검증 규칙은 인터페이스({@link CommentEngineService})에서 온다.
 * <p>
 * 하는 일은 셋뿐: ① domain으로 팩 찾기 ② 지표 · 섹션이 팩에 있는지 확인 ③ 팩의 generation.mode로 길 나누기.
 * {@code if (domain == "...")} 같은 분기는 없다 — 엔진은 팩이 적어 둔 방식을 따를 뿐이다.
 */
@RestController
public class CommentEngineController implements CommentEngineService {

    private final PackRegistry packs;
    private final TemplateGenerator template;
    private final LegacyAdapter legacy;

    public CommentEngineController(PackRegistry packs, TemplateGenerator template, LegacyAdapter legacy) {
        this.packs = packs;
        this.template = template;
        this.legacy = legacy;
    }

    @Override
    public LightCommentResult light(LightCommentRequest request) {
        long deadlineNanos = deadline(request.deadlineMs());
        DomainPack pack = packs.get(request.domain());
        for (MetricItem item : request.items()) {
            if (pack.metric(item.metric()).isEmpty()) {
                throw new EngineException(EngineErrorCode.INVALID_PAYLOAD,
                        "unknown metric '" + item.metric() + "' for domain '" + pack.id() + "'");
            }
        }
        return switch (pack.mode()) {
            case DomainPack.MODE_TEMPLATE -> template.light(request, pack);
            case DomainPack.MODE_LEGACY -> legacy.light(request, pack, deadlineNanos);
            // generic — A안 이후 추가. 팩 리더가 시작할 때 이미 막으므로 여기 오지 않는다
            default -> throw new IllegalStateException("unsupported mode " + pack.mode());
        };
    }

    @Override
    public SectionCommentResult section(SectionCommentRequest request) {
        long deadlineNanos = deadline(request.deadlineMs());
        DomainPack pack = packs.get(request.domain());
        var section = pack.section(request.section()).orElseThrow(() -> new EngineException(
                EngineErrorCode.INVALID_PAYLOAD,
                "unknown section '" + request.section() + "' for domain '" + pack.id() + "'"));
        return switch (pack.mode()) {
            case DomainPack.MODE_TEMPLATE -> template.section(request, section, pack);
            case DomainPack.MODE_LEGACY -> legacy.section(request, pack, deadlineNanos);
            default -> throw new IllegalStateException("unsupported mode " + pack.mode());
        };
    }

    @Override
    public List<DomainInfo> domains() {
        return packs.all().stream()
                .map(p -> new DomainInfo(p.id(), p.manifest().version(), p.manifest().specVersion(), p.mode(),
                        p.sectionIds()))
                .toList();
    }

    /** 마감 전파 — 받은 남은 시간을 지금 시각 기준 절대 마감으로 바꾼다 */
    private static long deadline(int deadlineMs) {
        return System.nanoTime() + deadlineMs * 1_000_000L;
    }
}
