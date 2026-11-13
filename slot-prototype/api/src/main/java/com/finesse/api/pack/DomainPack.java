package com.finesse.api.pack;

import java.util.List;
import java.util.Map;

/** 검사를 통과한 팩 하나. servers는 환경변수 덮어쓰기까지 반영한 최종 주소 */
public record DomainPack(PackManifest manifest, Map<String, MetricDef> metrics, List<String> servers) {

    public String id() { return manifest.id(); }

    public String mode() { return manifest.generation().mode(); }
}
