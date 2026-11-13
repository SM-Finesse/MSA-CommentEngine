package com.finesse.api.pack;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 칩 규격 0판 — 팩 폴더를 읽고 1단계 검사 4항목을 한다 (설계 방안 6.5절).
 * <ol>
 *   <li>필수 파일 · 필수 칸이 있다</li>
 *   <li>폴더 이름 = id, spec_version · generation.mode가 지원 목록 안</li>
 *   <li>light.candidates와 sections[].metrics의 키가 모두 metrics.json에 있다</li>
 *   <li>template 팩은 phrases 5칸이 모두 비어 있지 않다</li>
 * </ol>
 * 하나라도 실패하면 {@link PackValidationException}. 다른 파일은 무시한다(나중에 파일이 늘어도 0판 엔진이 깨지지 않게).
 * q1 ≤ q3, pick ≤ 후보 수 같은 나머지 검사는 2단계.
 */
public final class DomainPackReader {

    public static final Set<String> SUPPORTED_SPEC_VERSIONS = Set.of("0");
    public static final Set<String> SUPPORTED_MODES = Set.of(DomainPack.MODE_LEGACY, DomainPack.MODE_TEMPLATE);

    private static final List<String> PACK_REQUIRED =
            List.of("spec_version", "id", "version", "lang", "generation", "light", "sections", "no_data");
    private static final List<String> METRIC_REQUIRED = List.of("label", "decimals", "normal_range");
    private static final List<String> PHRASE_SLOTS = List.of("high", "low", "normal_pos", "normal_neg", "zero");

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** root 아래 폴더마다 팩 하나. 폴더 이름순으로 읽는다 */
    public Map<String, DomainPack> readAll(Path root) {
        if (!Files.isDirectory(root)) {
            throw new PackValidationException("packs dir not found: " + root.toAbsolutePath());
        }
        Map<String, DomainPack> packs = new TreeMap<>();
        try (Stream<Path> children = Files.list(root)) {
            for (Path dir : children.filter(Files::isDirectory).sorted().toList()) {
                DomainPack pack = read(dir);
                packs.put(pack.id(), pack);
            }
        } catch (IOException e) {
            throw new PackValidationException("cannot list packs dir: " + root.toAbsolutePath(), e);
        }
        if (packs.isEmpty()) {
            throw new PackValidationException("no packs in " + root.toAbsolutePath());
        }
        return Collections.unmodifiableMap(packs);
    }

    public DomainPack read(Path dir) {
        String folder = dir.getFileName().toString();

        // 1. 필수 파일 · 필수 칸
        Map<String, Object> packJson = readObject(dir.resolve("pack.json"), folder + "/pack.json");
        Map<String, Object> metricsJson = withoutComments(readObject(dir.resolve("metrics.json"), folder + "/metrics.json"));
        requireKeys(packJson, PACK_REQUIRED, folder + "/pack.json");
        requireKeys(asObject(packJson.get("generation"), folder + "/pack.json generation"),
                List.of("mode"), folder + "/pack.json generation");
        requireKeys(asObject(packJson.get("light"), folder + "/pack.json light"),
                List.of("candidates", "pick"), folder + "/pack.json light");
        if (!(packJson.get("sections") instanceof List<?> sections)) {
            throw new PackValidationException(folder + "/pack.json: sections must be an array");
        }
        for (int i = 0; i < sections.size(); i++) {
            requireKeys(asObject(sections.get(i), folder + "/pack.json sections[" + i + "]"),
                    List.of("id", "metrics"), folder + "/pack.json sections[" + i + "]");
        }

        Map<String, MetricDef> metrics = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : metricsJson.entrySet()) {
            String where = folder + "/metrics.json " + e.getKey();
            Map<String, Object> raw = asObject(e.getValue(), where);
            requireKeys(raw, METRIC_REQUIRED, where);
            metrics.put(e.getKey(), convert(raw, MetricDef.class, where));
        }
        PackManifest manifest = convert(packJson, PackManifest.class, folder + "/pack.json");

        // 2. 폴더 이름 = id, 지원하는 판 · 방식
        if (!folder.equals(manifest.id())) {
            throw new PackValidationException("pack id '" + manifest.id() + "' != folder '" + folder + "'");
        }
        if (!SUPPORTED_SPEC_VERSIONS.contains(manifest.specVersion())) {
            throw new PackValidationException(folder + ": spec_version '" + manifest.specVersion()
                    + "' not supported (supported: " + SUPPORTED_SPEC_VERSIONS + ")");
        }
        String mode = manifest.generation().mode();
        if (!SUPPORTED_MODES.contains(mode)) {
            throw new PackValidationException(folder + ": generation.mode '" + mode
                    + "' not supported (supported: " + SUPPORTED_MODES + ")");
        }

        // 3. pack.json에 나온 지표가 모두 metrics.json에 있다
        for (String m : manifest.light().candidates()) {
            if (!metrics.containsKey(m)) {
                throw new PackValidationException(folder + ": unknown metric '" + m + "' in light.candidates");
            }
        }
        for (PackManifest.Section s : manifest.sections()) {
            for (String m : s.metrics()) {
                if (!metrics.containsKey(m)) {
                    throw new PackValidationException(
                            folder + ": unknown metric '" + m + "' in sections[" + s.id() + "]");
                }
            }
        }

        // 4. template 팩은 phrases 5칸이 모두 있다
        if (DomainPack.MODE_TEMPLATE.equals(mode)) {
            for (Map.Entry<String, Object> e : metricsJson.entrySet()) {
                Object phrases = ((Map<?, ?>) e.getValue()).get("phrases");
                if (!(phrases instanceof Map<?, ?> p)) {
                    throw new PackValidationException(folder + ": " + e.getKey() + ".phrases missing");
                }
                for (String slot : PHRASE_SLOTS) {
                    if (!(p.get(slot) instanceof String text) || text.isBlank()) {
                        throw new PackValidationException(folder + ": " + e.getKey() + ".phrases." + slot + " is blank");
                    }
                }
            }
        }
        return new DomainPack(manifest, Collections.unmodifiableMap(metrics));
    }

    private Map<String, Object> readObject(Path file, String name) {
        if (!Files.isRegularFile(file)) {
            throw new PackValidationException(name + " missing");
        }
        try {
            Object parsed = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Object.class);
            return asObject(parsed, name);
        } catch (IOException | JacksonException e) {
            throw new PackValidationException(name + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** "_"로 시작하는 키는 메모용 — 지표로 보지 않는다 (프로토타입 규칙) */
    private static Map<String, Object> withoutComments(Map<String, Object> obj) {
        Map<String, Object> out = new LinkedHashMap<>(obj);
        out.keySet().removeIf(k -> k.startsWith("_"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new PackValidationException(name + " must be a JSON object");
    }

    private static void requireKeys(Map<String, Object> obj, List<String> keys, String name) {
        List<String> missing = new ArrayList<>();
        for (String k : keys) {
            if (!obj.containsKey(k)) {
                missing.add(k);
            }
        }
        if (!missing.isEmpty()) {
            throw new PackValidationException(name + ": required field " + missing + " missing");
        }
    }

    private <T> T convert(Map<String, Object> raw, Class<T> type, String name) {
        try {
            return mapper.convertValue(raw, type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new PackValidationException(name + ": wrong field type — " + e.getMessage(), e);
        }
    }
}
