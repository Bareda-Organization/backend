package src.backend.academy.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import src.backend.academy.command.StaffAccountCommandService;
import src.backend.academy.dto.StaffAccountDetailResponse;
import src.backend.academy.dto.StaffAccountSummaryResponse;
import src.backend.academy.dto.StaffAccountUpdateRequest;
import src.backend.academy.query.AdminStaffAccountQueryService;
import src.backend.account.dto.AdminAccountListRequest;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.authz.CanManageStaffAccount;

/**
 * 메인 관리자 콘솔의 관계자 계정 API(ACAD-06 · O-02, API_SPEC §6.6·§6.7).
 *
 * <p>전 학원 범위이며 학원 격리의 예외다(§1.5) — 대상 계정은 토큰이 아니라 경로 파라미터가 정한다.
 * 그래서 이 컨트롤러에는 {@code AuthUser} 를 받는 자리가 부재하다. 예외를 여는 판정은
 * {@link CanManageStaffAccount} 한 곳이고, 그 권한은 메인 관리자만 보유한다.
 */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin/staff-accounts")
@RequiredArgsConstructor
public class AdminStaffAccountController {

    private final AdminStaffAccountQueryService adminStaffAccountQueryService;

    private final StaffAccountCommandService staffAccountCommandService;

    /** 관계자 계정 목록(ACAD-06, §6.6). */
    @CanManageStaffAccount
    @Operation(summary = "관계자 계정 목록 (ACAD-06, O-02)")
    @GetMapping
    public ApiResponse<PageResponse<StaffAccountSummaryResponse>> list(
            @ModelAttribute AdminAccountListRequest request) {
        return ApiResponse.ok(adminStaffAccountQueryService.list(request));
    }

    /**
     * 관계자 계정 관리(ACAD-06, §6.7) — 정보 수정 · 비밀번호 초기화 · 퇴사·재직 전환.
     *
     * <p>학원 상세(§6.3)와 달리 쓰기 결과를 그대로 반환한다 — §1.9 가 요구하는 "변경 후 자원 상태" 에
     * 쓰기가 만들지 않는 집계가 들어가지 않아, 조회를 한 번 더 부를 이유가 부재하다.
     */
    @CanManageStaffAccount
    @Operation(summary = "관계자 계정 관리 (ACAD-06, O-02)")
    @PatchMapping("/{id}")
    public ApiResponse<StaffAccountDetailResponse> update(@PathVariable Long id,
            @Valid @RequestBody StaffAccountUpdateRequest request) {
        return ApiResponse.ok(staffAccountCommandService.update(id, request));
    }
}
