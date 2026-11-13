package com.finesse.api.engine.dto;

import java.util.List;

public record DomainInfo(String id, String version, String specVersion, String mode, List<String> sections) {
}
