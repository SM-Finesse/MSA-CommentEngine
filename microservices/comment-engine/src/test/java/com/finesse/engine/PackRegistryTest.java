package com.finesse.engine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finesse.api.pack.PackLoadException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackRegistryTest {

    /** 팩이 하나라도 잘못되면 PackRegistry 생성(= 엔진 시작)이 실패한다 */
    @Test
    void 잘못된_팩이_있으면_시작_실패(@TempDir Path tmp) throws IOException {
        Path good = Files.createDirectories(tmp.resolve("demo").resolve("pack"));
        for (String f : new String[]{"pack.json", "metrics.json"}) {
            Files.copy(Path.of("..", "..", "chips", "demo", "pack", f), good.resolve(f));
        }
        Path broken = Files.createDirectories(tmp.resolve("broken").resolve("pack"));
        Files.writeString(broken.resolve("pack.json"), "{}");

        assertThatThrownBy(() -> new PackRegistry(tmp.toString()))
                .isInstanceOf(PackLoadException.class)
                .hasMessage("broken/metrics.json missing");
    }
}
