package com.finesse.engine;

import com.finesse.api.engine.EngineErrorCode;
import com.finesse.api.engine.EngineException;
import com.finesse.api.pack.DomainPack;
import com.finesse.api.pack.DomainPackReader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 시작할 때 chips-dir 아래 칩마다 팩(<칩>/pack/)을 전부 읽는다. 하나라도 검사에 걸리면 예외 → 엔진이 뜨지 않는다 (fail-fast).
 * 꽂힌 팩은 실행 중에 바뀌지 않는다. 팩을 바꾸려면 재시작.
 */
@Component
public class PackRegistry {

    private static final Logger log = LoggerFactory.getLogger(PackRegistry.class);

    private final Map<String, DomainPack> packs = new LinkedHashMap<>();

    public PackRegistry(@Value("${engine.chips-dir}") String chipsDir) {
        Path root = Path.of(chipsDir);
        for (DomainPack pack : new DomainPackReader(System::getenv).readAll(root)) {
            packs.put(pack.id(), pack);
            log.info("pack loaded: {} v{} mode={} metrics={} sections={} servers={}",
                    pack.id(), pack.manifest().version(), pack.mode(),
                    pack.metrics().keySet(), pack.manifest().sections(), pack.servers());
        }
        log.info("{} pack(s) from {}", packs.size(), root.toAbsolutePath().normalize());
    }

    public DomainPack get(String domain) {
        DomainPack pack = packs.get(domain);
        if (pack == null) {
            throw new EngineException(EngineErrorCode.UNKNOWN_DOMAIN, "unknown domain '" + domain + "'. loaded: " + packs.keySet());
        }
        return pack;
    }

    public Collection<DomainPack> all() {
        return packs.values();
    }
}
