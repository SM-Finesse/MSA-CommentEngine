package com.finesse.api.engine.dto;

import java.util.List;

public record LightCommentResult(String summary, List<ItemSentence> items, boolean fallback, Versions versions) {
}
