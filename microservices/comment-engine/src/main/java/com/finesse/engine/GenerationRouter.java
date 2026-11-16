package com.finesse.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.dto.ItemSentence;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import com.finesse.api.engine.dto.Versions;
import com.finesse.api.pack.DomainPack;
import com.finesse.api.pack.Generation;
import com.finesse.engine.GenerationProtocol.GenText;
import com.finesse.engine.ServerGenerator.Failure;
import com.finesse.engine.ServerGenerator.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Component;

/**
 * 팩의 generation.mode에 따라 갈라진다 (확정안 7절).
 *   template → TemplateGenerator
 *   server   → 생성 서버 호출, 실패한 항목은 TemplateGenerator로 대체 (fallback = true)
 * <p>
 * light는 생성 서버 호출이 전부 실패해도 에러를 내지 않고 대체 문장으로 200을 돌려준다.
 * 지금 서비스(모놀리식 백엔드 light-v5.2, LlmClient.callLightComments)가 "절대 예외를 던지지 않고 문장 틀로 채운다"와 같다.
 * section은 생성 서버 호출이 실패하면 대체 문장 없이 에러 코드로 알린다 — 지금 서비스에서 섹션 단위 코멘트가 문장 틀 없이
 * 실패로 끝나는 것과 대응한다. light는 지표마다 팩의 phrases가 있어 뜻이 맞는 대체 문장을 만들 수 있지만, section은 넣을 수 있는
 * 문장이 no_data("값이 충분하지 않다")뿐이라 생성 서버 오류를 그 문장으로 덮으면 사실과 다른 말을 하게 된다.
 */
@Component
public class GenerationRouter {

    static final String ENGINE_VERSION = "0.1.0";

    private static final Logger log = LoggerFactory.getLogger(GenerationRouter.class);

    /** 응답을 만들어 돌려줄 여유 */
    private static final long MARGIN_MS = 200;

    private final TemplateGenerator template;
    private final GenerationProtocol protocol;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public GenerationRouter(TemplateGenerator template, GenerationProtocol protocol) {
        this.template = template;
        this.protocol = protocol;
    }

    public LightCommentResult light(DomainPack pack, LightCommentRequest req) {
        Versions versions = new Versions(ENGINE_VERSION, pack.manifest().version());
        if (Generation.TEMPLATE.equals(pack.mode())) {
            List<ItemSentence> items = req.items().stream()
                    .map(i -> new ItemSentence(i.metric(), template.sentence(pack, i), false))
                    .toList();
            return new LightCommentResult(template.summary(pack, req.items()), items, false, versions);
        }

        // 지표별 문장 N개 + 요약 1개를 동시에 부른다. 모두 같은 마감을 공유
        Instant deadline = Instant.now().plusMillis(req.deadlineMs() - MARGIN_MS);
        List<Future<Outcome<GenText>>> calls = new ArrayList<>();
        for (MetricItem item : req.items()) {
            calls.add(pool.submit(() -> protocol.sentence(pack, item, deadline)));
        }
        Future<Outcome<GenText>> summaryCall = pool.submit(() -> protocol.summary(pack, req.items(), deadline));

        List<Outcome<GenText>> sentences = calls.stream().map(GenerationRouter::join).toList();
        Outcome<GenText> summary = join(summaryCall);

        List<Failure> failures = new ArrayList<>();
        sentences.stream().filter(o -> !o.ok()).forEach(o -> failures.add(o.failure()));
        if (!summary.ok()) {
            failures.add(summary.failure());
        }
        if (failures.size() == sentences.size() + 1) {
            // 전부 실패해도 에러로 돌리지 않는다 (모놀리식 light-v5.2와 같음). 대신 남겨 두어 감시에서 보이게
            log.warn("[{}] light: 생성 서버 호출 {}건이 전부 실패 {} -> 대체 문장으로 응답", pack.id(), failures.size(), failures);
        }

        boolean anyFallback = false;
        List<ItemSentence> items = new ArrayList<>();
        for (int i = 0; i < req.items().size(); i++) {
            MetricItem item = req.items().get(i);
            Outcome<GenText> o = sentences.get(i);
            if (o.ok()) {
                items.add(new ItemSentence(item.metric(), o.value().text(), o.value().fallback()));
                anyFallback |= o.value().fallback();
            } else {
                items.add(new ItemSentence(item.metric(), template.sentence(pack, item), true));
                anyFallback = true;
            }
        }
        String summaryText;
        if (summary.ok()) {
            summaryText = summary.value().text();
            anyFallback |= summary.value().fallback();
        } else {
            summaryText = template.summary(pack, req.items());
            anyFallback = true;
        }
        return new LightCommentResult(summaryText, items, anyFallback, versions);
    }

    public SectionCommentResult section(DomainPack pack, SectionCommentRequest req) {
        Versions versions = new Versions(ENGINE_VERSION, pack.manifest().version());
        if (Generation.TEMPLATE.equals(pack.mode())) {
            return new SectionCommentResult(req.section(), template.section(pack), false, versions);
        }
        Instant deadline = Instant.now().plusMillis(req.deadlineMs() - MARGIN_MS);
        Outcome<GenText> o = protocol.section(pack, req.section(), req.data(), deadline);
        if (o.ok()) {
            return new SectionCommentResult(req.section(), o.value().text(), o.value().fallback(), versions);
        }
        throw sectionFailure(o.failure());
    }

    /**
     * section 호출이 끝내 실패했을 때의 에러 코드: 연결 실패 → 503, 시간 초과 → 504,
     * 답은 왔는데 쓸 수 없음(형식 오류, 생성 서버가 5xx 등 에러로 답함) → 502.
     */
    private static EngineException sectionFailure(Failure failure) {
        return switch (failure) {
            case UNAVAILABLE -> new EngineException(EngineErrorCode.LLM_UNAVAILABLE, "no generation server reachable");
            case TIMEOUT -> new EngineException(EngineErrorCode.LLM_TIMEOUT, "deadline exceeded");
            case FORMAT -> new EngineException(EngineErrorCode.LLM_FORMAT, "generation server returned bad format");
            case ERROR -> new EngineException(EngineErrorCode.LLM_FORMAT, "generation server answered with an error");
        };
    }

    private static <T> Outcome<T> join(Future<Outcome<T>> f) {
        try {
            return f.get();
        } catch (Exception e) {
            return Outcome.fail(Failure.ERROR);
        }
    }
}
