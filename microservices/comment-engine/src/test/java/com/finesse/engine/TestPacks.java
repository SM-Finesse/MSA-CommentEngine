package com.finesse.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 시험용 칩 폴더. 실제 chips의 팩(<칩>/pack/)만 임시 폴더로 복사하고 tetris 팩의 생성 서버 주소만 바꾼다.
 * 칩의 서비스 코드 · 설정은 복사하지 않는다 (엔진은 팩만 읽는다).
 * 엔진은 주소를 팩에서 읽으므로(C안), 시험도 팩을 바꿔서 주소를 넣는다.
 */
final class TestPacks {

    private static final Path SOURCE = Path.of("..", "..", "chips");

    private TestPacks() {
    }

    static Path withTetrisServers(String... servers) {
        try {
            Path root = Files.createTempDirectory("chips");
            List<Path> chips;
            try (Stream<Path> dirs = Files.list(SOURCE)) {
                chips = dirs.filter(Files::isDirectory).toList();
            }
            for (Path chip : chips) {
                Path to = Files.createDirectories(root.resolve(chip.getFileName().toString()).resolve("pack"));
                try (Stream<Path> files = Files.list(chip.resolve("pack"))) {
                    for (Path from : files.filter(Files::isRegularFile).toList()) {
                        Files.copy(from, to.resolve(from.getFileName().toString()));
                    }
                }
            }
            Path manifest = root.resolve("tetris").resolve("pack").resolve("pack.json");
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
