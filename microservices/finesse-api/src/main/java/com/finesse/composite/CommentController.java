package com.finesse.composite;

import com.finesse.composite.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면이 부르는 코멘트 경로 {@code GET /api/v1/comment/{username}?scope=light|heavy} (모놀리식과 같은 경로, API 명세서 4.2절).
 * <ul>
 *   <li>light: {@link LightCommentService}</li>
 *   <li>heavy: 아직 옮기지 않음 (E단계) → 501. 화면은 F단계 전까지 모놀리식을 부른다</li>
 *   <li>그 밖: 400 BAD_REQUEST, scope가 없으면 400 (둘 다 모놀리식과 같은 문장)</li>
 * </ul>
 */
@RestController
public class CommentController {

    private static final String PREFIX = "/api/v1/comment/";

    private final LightCommentService light;

    public CommentController(LightCommentService light) {
        this.light = light;
    }

    @GetMapping(PREFIX + "{username}")
    public ResponseEntity<?> comment(@RequestParam String scope, HttpServletRequest request) {
        return switch (scope) {
            case "light" -> light.light(rawUsername(request));
            case "heavy" -> ResponseEntity.status(501)
                    .body(new ApiErrorResponse("NOT_IMPLEMENTED", "heavy 코멘트는 아직 새 구조로 옮기지 않았습니다"));
            default -> throw new BadRequestException("scope는 light 또는 heavy만 허용됩니다: " + scope);
        };
    }

    /** 경로의 유저명을 받은 그대로(인코딩된 채로) — tetris-analytics에 다시 인코딩하지 않고 넘기기 위해 */
    private static String rawUsername(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.substring(uri.indexOf(PREFIX) + PREFIX.length());
    }
}
