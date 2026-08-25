package src.backend.account.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import src.backend.account.dto.MeResponse;
import src.backend.account.query.MeQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;
import src.backend.global.security.gate.AllowedWhenPending;

/**
 * 본인 프로필 조회(API_SPEC §2.10). {@code pending}·{@code rejected} 도 호출할 수 있다고
 * 본문이 명시해(대기 화면이 상태를 알아야 함) {@code @AllowedWhenPending} 을 붙인다 — 2026-08-25
 * 확인, MeControllerTest 리네임(Task 4).
 */
@RestController
public class MeController {

    private final MeQueryService meQueryService;

    public MeController(MeQueryService meQueryService) {
        this.meQueryService = meQueryService;
    }

    @AuthenticatedOnly
    @AllowedWhenPending
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(meQueryService.getMe(authUser.accountId()));
    }
}
