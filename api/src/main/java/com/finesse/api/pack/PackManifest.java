package com.finesse.api.pack;

import java.util.List;

/** ② 팩 규격 0판 — pack.json (확정안 4.2절) */
public record PackManifest(
        String specVersion,
        String id,
        String version,
        String lang,
        Generation generation,
        List<String> sections,
        String noData) {
}
