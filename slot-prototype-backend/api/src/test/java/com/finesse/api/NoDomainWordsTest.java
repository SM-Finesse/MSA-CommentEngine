package com.finesse.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시험 4 — 슬롯 코드에 분야 단어가 없다. 설계 방안 8장의 grep을 테스트로 옮긴 것.
 * (CI에서는 {@code grep -rE 'delta_|tetr|chapter|챕터' api/src/main} 0건과 같다)
 */
class NoDomainWordsTest {

    static final Pattern DOMAIN_WORDS = Pattern.compile("delta_|tetr|chapter|챕터", Pattern.CASE_INSENSITIVE);

    @Test
    void api_모듈_main_코드에_분야_단어_0건() throws IOException {
        assertThat(findDomainWords(Path.of("src", "main"))).isEmpty();
    }

    /** comment-engine 테스트도 같은 검사를 쓴다 */
    public static List<String> findDomainWords(Path root) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (DOMAIN_WORDS.matcher(lines.get(i)).find()) {
                        hits.add(f + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
            }
        }
        return hits;
    }
}
