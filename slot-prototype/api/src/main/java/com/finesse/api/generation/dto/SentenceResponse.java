package com.finesse.api.generation.dto;

public record SentenceResponse(String stat, String sentence, Boolean fallback, String version) {
}
