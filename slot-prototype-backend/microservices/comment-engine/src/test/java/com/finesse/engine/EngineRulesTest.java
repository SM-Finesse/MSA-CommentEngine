package com.finesse.engine;

import com.finesse.api.pack.PackValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineRulesTest {

    /** 본체 규칙 — 엔진 코드에도 분야 단어가 없다 (llm-server 쪽 이름은 application.yml에) */
    @Test
    void 엔진_main_코드에_분야_단어_0건() throws IOException {
        Pattern words = Pattern.compile("delta_|tetr|chapter|챕터", Pattern.CASE_INSENSITIVE);
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (words.matcher(lines.get(i)).find()) {
                        hits.add(f + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(hits).isEmpty();
    }

    /** fail fast — 팩이 하나라도 잘못되면 PackRegistry(=엔진 시작)가 실패한다 */
    @Test
    void 잘못된_팩이_있으면_시작_실패(@TempDir Path tmp) throws IOException {
        Path good = Files.createDirectories(tmp.resolve("demo"));
        for (String f : new String[]{"pack.json", "metrics.json"}) {
            Files.copy(Path.of("..", "..", "domain-packs", "demo", f), good.resolve(f));
        }
        Path broken = Files.createDirectories(tmp.resolve("broken"));
        Files.writeString(broken.resolve("pack.json"), "{}");

        assertThatThrownBy(() -> new PackRegistry(tmp))
                .isInstanceOf(PackValidationException.class)
                .hasMessage("broken/metrics.json missing");
    }
}
