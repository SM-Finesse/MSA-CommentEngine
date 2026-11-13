package com.finesse.api.engine;

import com.finesse.api.engine.dto.DomainInfo;
import com.finesse.api.engine.dto.LightCommentRequest;
import com.finesse.api.engine.dto.LightCommentResult;
import com.finesse.api.engine.dto.SectionCommentRequest;
import com.finesse.api.engine.dto.SectionCommentResult;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 엔진 계약 (설계 방안 5.1절) — 슬롯의 ① 절반.
 * <p>
 * comment-engine은 이 인터페이스를 <b>구현</b>하고(컨트롤러), finesse-api는 같은 인터페이스를 구현한
 * HTTP 클라이언트로 <b>호출</b>한다. 양쪽이 같은 타입을 보므로 칸 이름이 어긋나면 컴파일에서 걸린다.
 * <p>
 * 지표 · 섹션 이름은 문자열로만 받는다. 뜻은 domain으로 찾은 팩에 있다.
 */
public interface CommentEngineService {

    /** 수치 목록 → 지표별 문장 + 요약 */
    @PostMapping(value = "/v1/comments/light",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    LightCommentResult light(@Valid @RequestBody LightCommentRequest request);

    /** 섹션 1개 → 문장 1개. 여러 섹션의 순서 · 스트리밍은 부르는 쪽 몫 */
    @PostMapping(value = "/v1/comments/section",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    SectionCommentResult section(@Valid @RequestBody SectionCommentRequest request);

    /** 꽂혀 있는 팩 목록 */
    @GetMapping(value = "/v1/domains", produces = MediaType.APPLICATION_JSON_VALUE)
    List<DomainInfo> domains();
}
