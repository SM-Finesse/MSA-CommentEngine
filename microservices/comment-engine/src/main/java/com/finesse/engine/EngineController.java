package com.finesse.engine;

import com.finesse.api.engine.CommentEngineService;
import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.dto.DomainInfo;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import com.finesse.api.pack.DomainPack;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/** ① 엔진 계약 구현. 경로는 api 모듈의 인터페이스에 있다 */
@RestController
public class EngineController implements CommentEngineService {

    private final PackRegistry registry;
    private final GenerationRouter router;

    public EngineController(PackRegistry registry, GenerationRouter router) {
        this.registry = registry;
        this.router = router;
    }

    @Override
    public LightCommentResult light(LightCommentRequest request) {
        request.validate();
        DomainPack pack = registry.get(request.domain());
        for (MetricItem item : request.items()) {
            if (!pack.metrics().containsKey(item.metric())) {
                throw EngineException.invalid("unknown metric '" + item.metric() + "' for domain '" + pack.id() + "'");
            }
        }
        return router.light(pack, request);
    }

    @Override
    public SectionCommentResult section(SectionCommentRequest request) {
        request.validate();
        DomainPack pack = registry.get(request.domain());
        if (!pack.manifest().sections().contains(request.section())) {
            throw EngineException.invalid("unknown section '" + request.section() + "' for domain '" + pack.id() + "'");
        }
        return router.section(pack, request);
    }

    @Override
    public List<DomainInfo> domains() {
        return registry.all().stream()
                .map(p -> new DomainInfo(p.id(), p.manifest().version(), p.manifest().specVersion(),
                        p.mode(), p.manifest().sections()))
                .toList();
    }
}
