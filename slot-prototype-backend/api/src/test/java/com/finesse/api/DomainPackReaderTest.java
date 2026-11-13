package com.finesse.api;

import com.finesse.api.pack.DomainPack;
import com.finesse.api.pack.DomainPackReader;
import com.finesse.api.pack.PackValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 시험 3 — 팩 리더. 실제 팩은 통과, 6.5절 항목별로 고장 낸 팩 4개는 각자 맞는 메시지로 실패 */
class DomainPackReaderTest {

    static final Path PACKS = Path.of("..", "domain-packs");

    private final DomainPackReader reader = new DomainPackReader();

    @Test
    void 실제_팩_두_개가_통과() {
        Map<String, DomainPack> packs = reader.readAll(PACKS);
        assertThat(packs).containsOnlyKeys("demo", "tetris");
        assertThat(packs.get("demo").mode()).isEqualTo("template");
        assertThat(packs.get("tetris").mode()).isEqualTo("legacy");
        assertThat(packs.get("tetris").sectionIds()).hasSize(8);
        assertThat(packs.get("demo").metric("metric_b").orElseThrow().normalRange().q3()).isEqualTo(20.0);
    }

    @Test
    void 검사1_필수_파일이_없으면_실패(@TempDir Path tmp) throws IOException {
        Path demo = copyDemo(tmp, "demo");
        Files.delete(demo.resolve("metrics.json"));
        assertThatThrownBy(() -> reader.read(demo))
                .isInstanceOf(PackValidationException.class).hasMessage("demo/metrics.json missing");
    }

    @Test
    void 검사1_필수_칸이_없으면_실패(@TempDir Path tmp) throws IOException {
        Path demo = copyDemo(tmp, "demo");
        edit(demo.resolve("pack.json"), s -> s.replace("\"lang\": \"ko\",", ""));
        assertThatThrownBy(() -> reader.read(demo)).hasMessageContaining("required field [lang] missing");
    }

    @Test
    void 검사2_폴더_이름과_id가_다르면_실패(@TempDir Path tmp) throws IOException {
        Path demo = copyDemo(tmp, "demo");
        edit(demo.resolve("pack.json"), s -> s.replace("\"id\": \"demo\"", "\"id\": \"demo2\""));
        assertThatThrownBy(() -> reader.read(demo)).hasMessage("pack id 'demo2' != folder 'demo'");
    }

    @Test
    void 검사2_예약만_된_generic_방식은_실패(@TempDir Path tmp) throws IOException {
        Path demo = copyDemo(tmp, "demo");
        edit(demo.resolve("pack.json"), s -> s.replace("\"template\"", "\"generic\""));
        assertThatThrownBy(() -> reader.read(demo)).hasMessageContaining("generation.mode 'generic' not supported");
    }

    @Test
    void 검사3_metrics에_없는_지표를_쓰면_실패(@TempDir Path tmp) throws IOException {
        Path demo = copyDemo(tmp, "demo");
        edit(demo.resolve("pack.json"), s -> s.replace("\"metrics\": [\"metric_a\"", "\"metrics\": [\"metric_x\""));
        assertThatThrownBy(() -> reader.read(demo)).hasMessage("demo: unknown metric 'metric_x' in sections[overview]");
    }

    @Test
    void 검사4_template_팩의_phrases가_비면_실패(@TempDir Path tmp) throws IOException {
        Path demo = copyDemo(tmp, "demo");
        edit(demo.resolve("metrics.json"), s -> s.replace("\"A는 변화가 없습니다\"", "\"  \""));
        assertThatThrownBy(() -> reader.read(demo)).hasMessage("demo: metric_a.phrases.zero is blank");
    }

    @Test
    void 다른_파일은_무시(@TempDir Path tmp) throws IOException {
        Path demo = copyDemo(tmp, "demo");
        Files.writeString(demo.resolve("rules.json"), "{ \"2단계\": true }");
        assertThat(reader.read(demo).id()).isEqualTo("demo");
    }

    private static Path copyDemo(Path tmp, String folder) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(folder));
        for (String f : new String[]{"pack.json", "metrics.json"}) {
            Files.copy(PACKS.resolve("demo").resolve(f), dir.resolve(f));
        }
        return dir;
    }

    private static void edit(Path file, UnaryOperator<String> change) throws IOException {
        String before = Files.readString(file, StandardCharsets.UTF_8);
        String after = change.apply(before);
        assertThat(after).as("테스트가 고칠 글자를 찾지 못함: " + file).isNotEqualTo(before);
        Files.writeString(file, after, StandardCharsets.UTF_8);
    }
}
