package com.finesse.api.engine.dto;

import com.finesse.api.engine.EngineException;
import java.util.Map;

/** data는 자유 형식. 엔진은 검사하지 않고 생성 서버로 그대로 넘긴다 */
public record SectionCommentRequest(String domain, String section, Map<String, Object> data, Integer deadlineMs) {

    public void validate() {
        Deadline.check(deadlineMs);
        if (domain == null || domain.isBlank()) {
            throw EngineException.invalid("domain is required");
        }
        if (section == null || section.isBlank()) {
            throw EngineException.invalid("section is required");
        }
    }
}
