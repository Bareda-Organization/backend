package src.backend.student.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanLinkChild;
import src.backend.student.command.ChildLinkCommandService;
import src.backend.student.dto.LinkCodeIssueResponse;

/**
 * 학생 앱의 인증 코드 생성 API(S-05, API_SPEC §3.3).
 *
 * <p>연결 2단계의 <b>첫째</b>이고 유일하게 학생이 부르는 단계다(Ruling 324) — 선행 조건 없이
 * 학생이 언제든 호출할 수 있다. 요청 본문이 부재하다.
 *
 * <p>경로가 {@code /me/students/...} 계열이 아니라 {@code /me/link-code} 인 것은 사양 문자열
 * 그대로다(Ruling 80) — {@code @PublicEndpoint} 허용목록·계정 상태 게이트 대조가 "HTTP메서드+경로" 를
 * 키로 삼으므로 임의로 다듬지 않는다.
 */
@Tag(name = ApiTags.PARENT_STUDENT)
@RestController
@RequiredArgsConstructor
public class StudentLinkCodeController {

    private final ChildLinkCommandService childLinkCommandService;

    /** ① 인증 코드 생성(S-05, §3.3) — 선행 조건이 없다(Ruling 324). */
    @CanLinkChild
    @Operation(summary = "인증 코드 생성 (S-05) — 학생")
    @PostMapping("/me/link-code")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LinkCodeIssueResponse> issue(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(childLinkCommandService.issueCode(authUser));
    }
}
