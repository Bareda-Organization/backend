package src.backend.account.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.account.command.SignupApprovalCommandService;
import src.backend.account.dto.SignupDecisionPayload;
import src.backend.account.dto.SignupDecisionResponse;
import src.backend.account.dto.SignupRequestListRequest;
import src.backend.account.dto.SignupRequestListResponse;
import src.backend.account.query.SignupRequestQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanApproveSignup;

/**
 * 관계자 웹의 가입 승인 API(AUTH-10·11 · A-02, API_SPEC §5.1·§5.2).
 *
 * <p>대상은 학부모·학생·기사·동승자다 — 관계자({@code role=staff}) 가입은 메인 관리자 경로
 * ({@code AdminStaffApprovalController})가 처리한다. 두 컨트롤러가 나뉘어 있는 것이 인가 경계를
 * 경로 자체로 드러내는 방식이다.
 *
 * <p>소속 학원은 토큰이 정한다(§1.5) — 요청은 어느 학원인지 지정할 자리가 부재하다.
 */
@RestController
@RequestMapping("/staff/signup-requests")
@RequiredArgsConstructor
public class SignupApprovalController {

    private final SignupRequestQueryService signupRequestQueryService;

    private final SignupApprovalCommandService signupApprovalCommandService;

    /** 가입 요청 목록(AUTH-10, §5.1) — 기본은 미처리 건이다. */
    @CanApproveSignup
    @GetMapping
    public ApiResponse<SignupRequestListResponse> list(@AuthenticationPrincipal AuthUser authUser,
            @ModelAttribute SignupRequestListRequest request) {
        return ApiResponse.ok(signupRequestQueryService.forStaff(authUser, request));
    }

    /** 수락 · 거절(AUTH-10·11, §5.2) — 수락에는 계정 ↔ 레코드 연결이 함께 필요하다. */
    @CanApproveSignup
    @PostMapping("/{id}/decide")
    public ApiResponse<SignupDecisionResponse> decide(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long id, @Valid @RequestBody SignupDecisionPayload payload) {
        return ApiResponse.ok(signupApprovalCommandService.decide(authUser, id, payload));
    }
}
