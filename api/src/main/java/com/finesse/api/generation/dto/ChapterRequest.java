package com.finesse.api.generation.dto;

import java.util.Map;

/** 칸 이름 chapter_id는 동결된 llm-server 규약이라 그대로 둔다 */
public record ChapterRequest(String chapterId, Map<String, Object> data) {
}
