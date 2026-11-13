package com.finesse.api.engine.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** GET /v1/domains 원소 — 꽂혀 있는 팩 하나 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DomainInfo(String id, String version, String specVersion, String mode, List<String> sections) {
}
