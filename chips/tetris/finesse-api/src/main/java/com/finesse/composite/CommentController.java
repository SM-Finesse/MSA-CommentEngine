package com.finesse.composite;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면이 부르는 코멘트 경로 {@code GET /api/v1/comment/{username}?scope=light|heavy} (모놀리식과 같은 경로, API 명세서 4.2절).
 * <ul>
 *   <li>light: {@link LightCommentService}</li>
 *   <li>heavy: {@link HeavyCommentService} — SSE 스트림(text/event-stream). 챕터가 끝나는 대로 하나씩 보낸다</li>
 *   <li>그 밖: 400 BAD_REQUEST, scope가 없으면 400 (둘 다 모놀리식과 같은 문장)</li>
 * </ul>
 */
@RestController
public class CommentController {

    private static final String PREFIX = "/api/v1/comment/";

    private final LightCommentService light;
    private final HeavyCommentService heavy;

    public CommentController(LightCommentService light, HeavyCommentService heavy) {
        this.light = light;
        this.heavy = heavy;
    }

    @GetMapping(PREFIX + "{username}")
    public ResponseEntity<?> comment(@RequestParam String scope, HttpServletRequest request) {
        return switch (scope) {
            case "light" -> light.light(rawUsername(request));
            // X-Accel-Buffering: no — 앞단 프록시(nginx 등)가 스트림을 모아서 한꺼번에 내보내지 않게 (모놀리식과 같은 헤더)
            case "heavy" -> ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .body(heavy.stream(rawUsername(request)));
            default -> throw new BadRequestException("scope는 light 또는 heavy만 허용됩니다: " + scope);
        };
    }

    /** 경로의 유저명을 받은 그대로(인코딩된 채로) — tetris-analytics에 다시 인코딩하지 않고 넘기기 위해 */
    private static String rawUsername(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.substring(uri.indexOf(PREFIX) + PREFIX.length());
    }
}
