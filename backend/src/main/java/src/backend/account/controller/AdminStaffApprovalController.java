package src.backend.account.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.account.command.StaffApprovalCommandService;
import src.backend.account.dto.SignupDecisionResponse;
import src.backend.account.dto.SignupRequestListRequest;
import src.backend.account.dto.StaffDecisionPayload;
import src.backend.account.dto.StaffSignupRequestSummaryResponse;
import src.backend.account.query.SignupRequestQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanApproveStaff;

/**
 * 메인 관리자 콘솔의 관계자 가입 승인 API(ACAD-05 · O-02, API_SPEC §6.4·§6.5).
 *
 * <p>전 학원 범위이며 학원 격리의 예외다(§1.5) — 관계자 축과 달리 목록에 학원이 함께 실린다.
 * 예외를 여는 판정은 {@link CanApproveStaff} 한 곳이고, 그 권한은 메인 관리자만 보유한다.
 *
 * <p>{@code AuthUser} 를 받는 것은 학원을 정하기 위해서가 아니라 <b>처리자를 기록</b>하기 위해서다
 * ({@code signup_request.decided_by}) — 그 값이 없으면 "누가 승인했나" 를 판정할 수단이 부재하다.
 */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin/staff-signup-requests")
@RequiredArgsConstructor
public class AdminStaffApprovalController {

    private final SignupRequestQueryService signupRequestQueryService;

    private final StaffApprovalCommandService staffApprovalCommandService;

    /** 관계자 가입 요청 목록(ACAD-05, §6.4). */
    @CanApproveStaff
    @Operation(summary = "관계자 가입 요청 목록 (ACAD-05, O-02)")
    @GetMapping
    public ApiResponse<PageResponse<StaffSignupRequestSummaryResponse>> list(
            @ModelAttribute SignupRequestListRequest request) {
        return ApiResponse.ok(signupRequestQueryService.forAdmin(request));
    }

    /** 관계자 가입 수락 · 거절(ACAD-05, §6.5) — 수락은 학원당 1명 정원을 지난다. */
    @CanApproveStaff
    @Operation(summary = "관계자 가입 수락 / 거절 (ACAD-05, O-02)")
    @PostMapping("/{id}/decide")
    public ApiResponse<SignupDecisionResponse> decide(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long id, @Valid @RequestBody StaffDecisionPayload payload) {
        return ApiResponse.ok(staffApprovalCommandService.decide(authUser.accountId(), id, payload));
    }
}
