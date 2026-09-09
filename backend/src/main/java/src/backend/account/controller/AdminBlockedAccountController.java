package src.backend.account.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.account.command.AccountUnblockCommandService;
import src.backend.account.dto.AccountUnblockResponse;
import src.backend.account.dto.AdminAccountListRequest;
import src.backend.account.dto.BlockedAccountResponse;
import src.backend.account.query.AdminBlockedAccountQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanUnblockAccount;

/**
 * 메인 관리자 콘솔의 로그인 차단 해제 API(AUTH-06 · O-03, API_SPEC §6.10·§6.12).
 *
 * <p>전 학원 범위이며 학원 격리의 예외다(§1.5) — 대상은 학원이 아니라 계정이라, 이 컨트롤러는
 * 호출자의 소속 학원을 보지 않는다. {@link AuthUser} 를 받는 것은 격리 판정이 아니라 <b>처리자를
 * 이력에 남기기 위해서</b>다(§6.12 "처리자·일시 저장").
 */
@Tag(name = ApiTags.ADMIN)
@RestController
@RequestMapping("/admin/blocked-accounts")
@RequiredArgsConstructor
public class AdminBlockedAccountController {

    private final AdminBlockedAccountQueryService adminBlockedAccountQueryService;

    private final AccountUnblockCommandService accountUnblockCommandService;

    /** 차단 계정 목록(AUTH-06, §6.10). */
    @CanUnblockAccount
    @Operation(summary = "차단 계정 목록 (AUTH-06, O-03)")
    @GetMapping
    public ApiResponse<PageResponse<BlockedAccountResponse>> list(@ModelAttribute AdminAccountListRequest request) {
        return ApiResponse.ok(adminBlockedAccountQueryService.list(request));
    }

    /** 로그인 차단 해제(AUTH-06, §6.12) — 요청 본문은 부재하고 대상은 경로가 정한다. */
    @CanUnblockAccount
    @Operation(summary = "로그인 차단 해제 (AUTH-06, O-03)")
    @PostMapping("/{id}/unblock")
    public ApiResponse<AccountUnblockResponse> unblock(@PathVariable Long id,
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(accountUnblockCommandService.unblock(id, authUser.accountId()));
    }
}
