package com.finesse.api.pack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 읽고 검사를 마친 팩 하나 = pack.json + metrics.json */
public record DomainPack(PackManifest manifest, Map<String, MetricDef> metrics) {

    public static final String MODE_LEGACY = "legacy";
    public static final String MODE_TEMPLATE = "template";

    public String id() {
        return manifest.id();
    }

    public String mode() {
        return manifest.generation().mode();
    }

    public Optional<MetricDef> metric(String name) {
        return Optional.ofNullable(metrics.get(name));
    }

    public Optional<PackManifest.Section> section(String id) {
        return manifest.sections().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public List<String> sectionIds() {
        return manifest.sections().stream().map(PackManifest.Section::id).toList();
    }
}
