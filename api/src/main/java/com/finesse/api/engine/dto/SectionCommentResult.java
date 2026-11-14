package com.finesse.api.engine.dto;

public record SectionCommentResult(String section, String text, boolean fallback, Versions versions) {
}
