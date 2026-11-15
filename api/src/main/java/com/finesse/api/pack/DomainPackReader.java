package com.finesse.api.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 팩 폴더를 읽고 확정안 4.5절 검사 4개를 한다. 하나라도 틀리면 PackLoadException.
 *  1. 필수 파일 · 칸이 있음
 *  2. 폴더 이름 = id, spec_version이 지원 목록 안
 *  3. phrases 5칸이 모두 비어 있지 않음
 *  4. server 모드면 servers가 1개 이상 (환경변수 FINESSE_PACK_{ID}_SERVERS가 있으면 그게 우선)
 */
public final class DomainPackReader {

    public static final Set<String> SUPPORTED_SPEC_VERSIONS = Set.of("0");
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]*$");

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final Function<String, String> env;

    /** env: 환경변수 조회 함수. 실제로는 System::getenv, 시험에서는 가짜를 넣는다 */
    public DomainPackReader(Function<String, String> env) {
        this.env = env;
    }

    /** root 아래 폴더마다 팩 하나. 하나라도 실패하면 전부 실패 */
    public List<DomainPack> readAll(Path root) {
        if (!Files.isDirectory(root)) {
            throw new PackLoadException("pack root not found: " + root.toAbsolutePath());
        }
        List<DomainPack> packs = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                packs.add(read(dir));
            }
        } catch (IOException e) {
            throw new PackLoadException("cannot list " + root + ": " + e.getMessage());
        }
        return packs;
    }

    public DomainPack read(Path dir) {
        String folder = dir.getFileName().toString();

        // 검사 1: 필수 파일
        PackManifest m = readJson(dir, "pack.json", new TypeReference<PackManifest>() { });
        Map<String, MetricDef> metrics = readJson(dir, "metrics.json", new TypeReference<LinkedHashMap<String, MetricDef>>() { });

        // 검사 1: 필수 칸
        require(m.specVersion(), folder, "spec_version");
        require(m.id(), folder, "id");
        require(m.version(), folder, "version");
        require(m.lang(), folder, "lang");
        require(m.noData(), folder, "no_data");
        if (m.generation() == null || m.generation().mode() == null) {
            throw new PackLoadException(folder + "/pack.json: generation.mode missing");
        }
        if (m.sections() == null) {
            throw new PackLoadException(folder + "/pack.json: sections missing");
        }

        // 검사 2: 폴더 이름 = id, 지원하는 spec_version
        if (!ID.matcher(m.id()).matches()) {
            throw new PackLoadException("pack id '" + m.id() + "' must match " + ID.pattern());
        }
        if (!m.id().equals(folder)) {
            throw new PackLoadException("pack id '" + m.id() + "' != folder '" + folder + "'");
        }
        if (!SUPPORTED_SPEC_VERSIONS.contains(m.specVersion())) {
            throw new PackLoadException(folder + ": spec_version '" + m.specVersion() + "' not in " + SUPPORTED_SPEC_VERSIONS);
        }

        // 검사 3: 지표마다 label · decimals · phrases 5칸
        if (metrics.isEmpty()) {
            throw new PackLoadException(folder + "/metrics.json: no metrics");
        }
        metrics.forEach((key, def) -> checkMetric(folder, key, def));

        // 검사 4: 생성 방식
        String mode = m.generation().mode();
        List<String> servers = List.of();
        if (Generation.SERVER.equals(mode)) {
            servers = serversOf(m);
            if (servers.isEmpty()) {
                throw new PackLoadException(folder + ": generation.servers is empty");
            }
        } else if (!Generation.TEMPLATE.equals(mode)) {
            throw new PackLoadException(folder + ": generation.mode must be server | template, got '" + mode + "'");
        }
        return new DomainPack(m, Collections.unmodifiableMap(metrics), servers);
    }

    /** 환경변수 FINESSE_PACK_{ID}_SERVERS (쉼표 구분)가 있으면 pack.json보다 우선 */
    private List<String> serversOf(PackManifest m) {
        String key = "FINESSE_PACK_" + m.id().toUpperCase().replace('-', '_') + "_SERVERS";
        String override = env.apply(key);
        if (override != null && !override.isBlank()) {
            return Stream.of(override.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
        return m.generation().servers() == null ? List.of() : List.copyOf(m.generation().servers());
    }

    private static void checkMetric(String folder, String key, MetricDef def) {
        String where = folder + "/metrics.json: " + key;
        if (def == null) {
            throw new PackLoadException(where + " is null");
        }
        require(def.label(), where, "label");
        if (def.decimals() == null || def.decimals() < 0 || def.decimals() > 4) {
            throw new PackLoadException(where + ".decimals must be 0..4");
        }
        Phrases p = def.phrases();
        if (p == null) {
            throw new PackLoadException(where + ".phrases missing");
        }
        blank(p.high(), key, "high");
        blank(p.low(), key, "low");
        blank(p.normalPos(), key, "normal_pos");
        blank(p.normalNeg(), key, "normal_neg");
        blank(p.zero(), key, "zero");
        // 선택 칸: 없으면(null) normal 칸을 쓴다. 적었는데 비어 있으면 실수로 본다
        if (p.weakPos() != null) {
            blank(p.weakPos(), key, "weak_pos");
        }
        if (p.weakNeg() != null) {
            blank(p.weakNeg(), key, "weak_neg");
        }
    }

    private static void blank(String v, String key, String slot) {
        if (v == null || v.isBlank()) {
            throw new PackLoadException(key + ".phrases." + slot + " is blank");
        }
    }

    private static void require(String v, String where, String field) {
        if (v == null || v.isBlank()) {
            throw new PackLoadException(where + ": " + field + " missing");
        }
    }

    private <T> T readJson(Path dir, String file, TypeReference<T> type) {
        Path p = dir.resolve(file);
        String folder = dir.getFileName().toString();
        if (!Files.isRegularFile(p)) {
            throw new PackLoadException(folder + "/" + file + " missing");
        }
        try {
            T value = mapper.readValue(Files.readString(p), type);
            if (value == null) {
                throw new PackLoadException(folder + "/" + file + " is empty");
            }
            return value;
        } catch (IOException | JacksonException e) {
            throw new PackLoadException(folder + "/" + file + " is not valid JSON: " + e.getMessage());
        }
    }
}
