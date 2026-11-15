package com.finesse.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.finesse.api.engine.EngineException;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.MetricItem;
import com.finesse.api.engine.dto.Zone;
import com.finesse.api.pack.DomainPack;
import com.finesse.api.pack.DomainPackReader;
import com.finesse.api.pack.PackLoadException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** 확정안 9.1절 — 1단계 시험 4개 */
class Stage1Test {

    private static final Path CHIPS = Path.of("../chips");
    private final JsonMapper json = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    // ---------- 시험 1: DTO ↔ JSON 왕복, 키가 모두 snake_case ----------

    @Test
    void test1_lightRequestRoundTrip() {
        String src = """
                {"domain":"tetris","items":[{"metric":"delta_app","value":0.05,"zone":"high"},
                 {"metric":"delta_opener","value":-0.12,"zone":"low"}],"deadline_ms":35000}""";
        LightCommentRequest req = json.readValue(src, LightCommentRequest.class);
        assertEquals(35000, req.deadlineMs());
        assertEquals(Zone.LOW, req.items().get(1).zone());
        assertEquals(json.readTree(src), json.readTree(json.writeValueAsString(req)));
    }

    @Test
    void test1_lightResultRoundTrip() {
        String src = """
                {"summary":"s","items":[{"metric":"delta_app","sentence":"x","fallback":false}],
                 "fallback":false,"versions":{"engine":"0.1.0","pack":"2026.11"}}""";
        LightCommentResult res = json.readValue(src, LightCommentResult.class);
        JsonNode back = json.readTree(json.writeValueAsString(res));
        assertEquals(json.readTree(src), back);
        assertAllKeysSnakeCase(json.readTree(json.writeValueAsString(
                json.readValue("{\"domain\":\"d\",\"items\":[],\"deadline_ms\":1000}", LightCommentRequest.class))));
    }

    private static void assertAllKeysSnakeCase(JsonNode node) {
        Pattern snake = Pattern.compile("^[a-z][a-z0-9_]*$");
        if (node.isObject()) {
            for (String key : node.propertyNames()) {
                assertTrue(snake.matcher(key).matches(), "not snake_case: " + key);
                assertAllKeysSnakeCase(node.get(key));
            }
        } else if (node.isArray()) {
            node.forEach(Stage1Test::assertAllKeysSnakeCase);
        }
    }

    // ---------- 시험 2: 입력 검증 ----------

    private static LightCommentRequest req(List<MetricItem> items, Integer deadlineMs) {
        return new LightCommentRequest("demo", items, deadlineMs);
    }

    private static final MetricItem OK_ITEM = new MetricItem("metric_a", 0.3, Zone.HIGH);

    @Test
    void test2_validation() {
        assertThrows(EngineException.class, () -> req(List.of(), 40000).validate(), "빈 items");
        assertThrows(EngineException.class, () -> req(List.of(OK_ITEM), 0).validate(), "deadline_ms 0");
        assertThrows(EngineException.class,
                () -> req(List.of(new MetricItem("metric_a", 1.0, Zone.from("none"))), 40000).validate(), "zone none");
        assertThrows(EngineException.class,
                () -> req(List.of(new MetricItem("metric_a", Double.NaN, Zone.HIGH)), 40000).validate(), "NaN");
        assertDoesNotThrow(() -> req(List.of(OK_ITEM), 40000).validate());
    }

    // ---------- 시험 3: 팩 리더 ----------

    private final DomainPackReader reader = new DomainPackReader(k -> null);

    @Test
    void test3_realPacksPass() {
        List<DomainPack> packs = reader.readAll(CHIPS);
        assertEquals(List.of("demo", "tetris"), packs.stream().map(DomainPack::id).toList());
    }

    @Test
    void test3_envOverridesServers() {
        DomainPack tetris = new DomainPackReader(k -> k.equals("FINESSE_PACK_TETRIS_SERVERS") ? "http://a:1, http://b:2" : null)
                .read(CHIPS.resolve("tetris"));
        assertEquals(List.of("http://a:1", "http://b:2"), tetris.servers());
    }

    @Test
    void test3_brokenPacksFailWithRightMessage(@TempDir Path tmp) throws IOException {
        // 1. 필수 파일 없음
        Path p1 = copyDemo(tmp, "demo");
        Files.delete(p1.resolve("pack/metrics.json"));
        assertMessage(p1, "demo/metrics.json missing");

        // 2. 폴더 이름 != id
        Path p2 = copyDemo(tmp.resolve("x2"), "demo2");
        assertMessage(p2, "pack id 'demo' != folder 'demo2'");

        // 3. phrases 한 칸이 빔
        Path p3 = copyDemo(tmp.resolve("x3"), "demo");
        replace(p3.resolve("pack/metrics.json"), "\"A는 변화가 없습니다\"", "\"  \"");
        assertMessage(p3, "metric_a.phrases.zero is blank");

        // 4. server 모드인데 servers 없음
        Path p4 = copyDemo(tmp.resolve("x4"), "demo");
        replace(p4.resolve("pack/pack.json"), "{ \"mode\": \"template\" }", "{ \"mode\": \"server\", \"servers\": [] }");
        assertMessage(p4, "demo: generation.servers is empty");

        // 6. 칩 폴더에 pack 폴더가 없음 (서비스 코드만 있는 폴더는 칩이 아니다)
        Path p6 = Files.createDirectories(tmp.resolve("x6").resolve("nopack"));
        assertMessage(p6, "nopack/pack folder missing");

        // 5. 선택 칸(weak_pos)을 적었는데 비어 있음. 안 적은 것은 괜찮다 (demo·tetris 팩이 그대로 통과 — test3_realPacksPass)
        Path p5 = copyDemo(tmp.resolve("x5"), "demo");
        replace(p5.resolve("pack/metrics.json"), "\"zero\": \"A는 변화가 없습니다\"",
                "\"zero\": \"A는 변화가 없습니다\", \"weak_pos\": \"\"");
        assertMessage(p5, "metric_a.phrases.weak_pos is blank");
    }

    private void assertMessage(Path dir, String expected) {
        PackLoadException e = assertThrows(PackLoadException.class, () -> reader.read(dir));
        assertEquals(expected, e.getMessage());
    }

    /** demo 칩의 팩을 parent/folder/pack/ 으로 복사하고 칩 폴더(parent/folder)를 돌려준다 */
    private static Path copyDemo(Path parent, String folder) throws IOException {
        Path chip = parent.resolve(folder);
        Path pack = Files.createDirectories(chip.resolve("pack"));
        for (String f : List.of("pack.json", "metrics.json")) {
            Files.copy(CHIPS.resolve("demo").resolve("pack").resolve(f), pack.resolve(f));
        }
        return chip;
    }

    private static void replace(Path file, String from, String to) throws IOException {
        String s = Files.readString(file);
        assertTrue(s.contains(from), "fixture text not found: " + from);
        Files.writeString(file, s.replace(from, to));
    }

    // ---------- 시험 4: 본체 코드에 테트리스 단어 없음 ----------

    /**
     * 보는 곳: 슬롯(api), 본체(comment-engine, gateway)의 코드와 본체 설정(config-repo). 칩(chips/)은 보지 않는다.
     * 동결된 llm-server 규약(chapter_id 등)을 그대로 쓰는 두 곳은 뺀다:
     * api/.../generation/ 과 comment-engine의 GenerationProtocol.java
     */
    @Test
    void test4_noDomainWordsInBody() throws IOException {
        Pattern words = Pattern.compile("delta_|tetr|chapter|챕터|finesse-api", Pattern.CASE_INSENSITIVE);
        List<String> hits = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main"), Path.of("../microservices/comment-engine/src/main"),
                Path.of("../spring-cloud/gateway/src/main"), Path.of("../config-repo"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String path = f.toString().replace('\\', '/');
                    if (path.contains("/generation/") || path.endsWith("GenerationProtocol.java")) {
                        continue;
                    }
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        if (words.matcher(lines.get(i)).find()) {
                            hits.add(path + ":" + (i + 1) + "  " + lines.get(i).strip());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), hits, String.join("\n", hits));
    }
}
