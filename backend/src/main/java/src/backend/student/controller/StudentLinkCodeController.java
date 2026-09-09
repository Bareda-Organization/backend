package src.backend.student.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanLinkChild;
import src.backend.student.command.ChildLinkCommandService;
import src.backend.student.dto.LinkCodeIssueResponse;

/**
 * 학생 앱의 인증 코드 생성 API(S-05, API_SPEC §3.3).
 *
 * <p>연결 3단계의 <b>가운데</b>이고 유일하게 학생이 부르는 단계다. 요청 본문이 부재하므로 어느 연결
 * 요청에 코드를 붙일지는 서버가 정한다 — 학생은 "코드를 만들어 달라" 만 말한다.
 *
 * <p>경로가 {@code /me/students/...} 계열이 아니라 {@code /me/link-code} 인 것은 사양 문자열
 * 그대로다(Ruling 80) — {@code @PublicEndpoint} 허용목록·계정 상태 게이트 대조가 "HTTP메서드+경로" 를
 * 키로 삼으므로 임의로 다듬지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class StudentLinkCodeController {

    private final ChildLinkCommandService childLinkCommandService;

    /** ② 인증 코드 생성(S-05, §3.3) — 대기 중인 연결 요청이 선행 조건이다. */
    @CanLinkChild
    @PostMapping("/me/link-code")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LinkCodeIssueResponse> issue(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(childLinkCommandService.issueCode(authUser));
    }
}
