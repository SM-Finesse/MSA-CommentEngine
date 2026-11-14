package com.finesse.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 시험용 팩 폴더. 실제 domain-packs를 임시 폴더로 복사하고 tetris 팩의 생성 서버 주소만 바꾼다.
 * 엔진은 주소를 팩에서 읽으므로(C안), 시험도 팩을 바꿔서 주소를 넣는다.
 */
final class TestPacks {

    private static final Path SOURCE = Path.of("..", "..", "domain-packs");

    private TestPacks() {
    }

    static Path withTetrisServers(String... servers) {
        try {
            Path root = Files.createTempDirectory("packs");
            try (Stream<Path> files = Files.walk(SOURCE)) {
                for (Path from : files.toList()) {
                    Path to = root.resolve(SOURCE.relativize(from).toString());
                    if (Files.isDirectory(from)) {
                        Files.createDirectories(to);
                    } else {
                        Files.copy(from, to);
                    }
                }
            }
            Path manifest = root.resolve("tetris").resolve("pack.json");
            String before = Files.readString(manifest);
            String list = Arrays.stream(servers).map(s -> "\"" + s + "\"").collect(Collectors.joining(", "));
            String after = before.replaceFirst("\"servers\"\\s*:\\s*\\[[^\\]]*\\]", "\"servers\": [" + list + "]");
            if (after.equals(before)) {
                throw new IllegalStateException("servers not found in " + manifest);
            }
            Files.writeString(manifest, after);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
