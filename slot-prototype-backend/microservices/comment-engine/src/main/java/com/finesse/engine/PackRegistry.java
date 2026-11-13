package com.finesse.engine;

import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.pack.DomainPack;
import com.finesse.api.pack.DomainPackReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * 시작할 때 팩을 전부 읽고 검사한다. 하나라도 실패하면 생성자가 예외를 던져 <b>엔진이 시작하지 않는다</b> (fail fast).
 * 영업 중에 사용자 요청으로 "팩이 이상하다"를 발견하는 대신, 배포하는 사람이 바로 알게.
 */
@Component
public class PackRegistry {

    private static final Logger log = LoggerFactory.getLogger(PackRegistry.class);

    private final Map<String, DomainPack> packs;

    @Autowired
    public PackRegistry(EngineProperties properties) {
        this(Path.of(properties.packsDir()));
    }

    PackRegistry(Path packsDir) {
        this.packs = new DomainPackReader().readAll(packsDir);
        packs.values().forEach(p -> log.info("팩 로드: {} v{} (spec {}, mode {}, 지표 {}개, 섹션 {}개)",
                p.id(), p.manifest().version(), p.manifest().specVersion(), p.mode(),
                p.metrics().size(), p.sectionIds().size()));
    }

    public DomainPack get(String domain) {
        DomainPack pack = packs.get(domain);
        if (pack == null) {
            throw new EngineException(EngineErrorCode.UNKNOWN_DOMAIN, "domain '" + domain + "' is not loaded");
        }
        return pack;
    }

    public Collection<DomainPack> all() {
        return packs.values();
    }
}
