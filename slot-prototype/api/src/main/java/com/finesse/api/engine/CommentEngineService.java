package com.finesse.api.engine;

import com.finesse.api.engine.dto.DomainInfo;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * ① 엔진 계약 (칩 → 본체). 수업 repo의 ProductService처럼 경로를 인터페이스에 둔다.
 * 본체(comment-engine)는 이걸 구현하고, 칩(finesse-api)은 이 경로로 부른다.
 */
public interface CommentEngineService {

    @PostMapping(value = "/v1/comments/light", consumes = "application/json", produces = "application/json")
    LightCommentResult light(@RequestBody LightCommentRequest request);

    @PostMapping(value = "/v1/comments/section", consumes = "application/json", produces = "application/json")
    SectionCommentResult section(@RequestBody SectionCommentRequest request);

    @GetMapping(value = "/v1/domains", produces = "application/json")
    List<DomainInfo> domains();
}
