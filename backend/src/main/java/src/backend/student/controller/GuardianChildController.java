package src.backend.student.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanLinkChild;
import src.backend.global.security.authz.CanReadLinkedChild;
import src.backend.student.command.ChildLinkCommandService;
import src.backend.student.dto.ChildLinkSubmitRequest;
import src.backend.student.dto.ChildLinkedResponse;
import src.backend.student.dto.ChildListResponse;
import src.backend.student.dto.LinkRequestCreateRequest;
import src.backend.student.dto.LinkRequestCreatedResponse;
import src.backend.student.query.ChildQueryService;

/**
 * 학부모 앱의 자녀 목록·연결 API(ATT-03 · P-02, API_SPEC §3.1·§3.2·§3.4).
 *
 * <p>연결 3단계 중 <b>학부모가 하는 두 단계</b>만 여기 있다 — 가운데의 코드 생성은 학생이 부르므로
 * {@link StudentLinkCodeController} 다. 주체가 다르면 인가·자원 해석이 함께 갈리므로 한 클래스에
 * 담지 않는다.
 *
 * <p>보호자는 토큰이 정한다(§1.5) — 요청에 어느 보호자인지 지정할 자리가 부재하다.
 *
 * <p>계정 상태 게이트 애너테이션({@code @AllowedWhenPending} 등)은 붙지 않는다 — 자녀 연결은 승인된
 * 학부모의 기능이라 승인 대기·거절 계정이 닿을 이유가 부재하고, 허용 목록 밖으로 남아 {@code 403} 이
 * 되는 것이 사양이다(§1.4 · Ruling 145).
 */
@RestController
@RequestMapping("/me/students")
@RequiredArgsConstructor
public class GuardianChildController {

    private final ChildQueryService childQueryService;

    private final ChildLinkCommandService childLinkCommandService;

    /** 연결된 자녀 목록(ATT-03, §3.1) — 자녀 선택 UI 와 알림 문구의 재료다. */
    @CanReadLinkedChild
    @GetMapping
    public ApiResponse<ChildListResponse> list(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(childQueryService.list(authUser));
    }

    /** ① 자녀 연결 요청(P-02, §3.2) — 대상 학생을 로그인 아이디로 지목한다. */
    @CanLinkChild
    @PostMapping("/link-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LinkRequestCreatedResponse> requestLink(@AuthenticationPrincipal AuthUser authUser,
            @Valid @RequestBody LinkRequestCreateRequest request) {
        return ApiResponse.ok(childLinkCommandService.requestLink(authUser, request.studentLoginId()));
    }

    /**
     * ③ 코드 입력 → 연결 완료(P-02, §3.4).
     *
     * <p>응답에 코드를 되돌려주지 않는다 — 대조는 서버가 하고 클라이언트는 비교하지 않는다는 것이
     * 이 단계의 전제다(§3.4).
     */
    @CanLinkChild
    @PostMapping("/link")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChildLinkedResponse> link(@AuthenticationPrincipal AuthUser authUser,
            @Valid @RequestBody ChildLinkSubmitRequest request) {
        return ApiResponse.ok(childLinkCommandService.completeLink(authUser, request.code()));
    }
}
