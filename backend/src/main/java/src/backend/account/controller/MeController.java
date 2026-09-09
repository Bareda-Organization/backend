package src.backend.account.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.account.dto.MeResponse;
import src.backend.account.query.MeQueryService;
import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.AuthenticatedOnly;
import src.backend.global.security.gate.AllowedWhenPending;

/**
 * 본인 프로필 조회(API_SPEC §2.10). {@code pending}·{@code rejected} 도 호출할 수 있다고
 * 본문이 명시해(대기 화면이 상태를 알아야 함) {@code @AllowedWhenPending} 을 붙인다 — 2026-08-25
 * 확인, MeControllerTest 리네임(Task 4).
 */
@Tag(name = ApiTags.AUTH)
@RestController
@RequiredArgsConstructor
public class MeController {

    private final MeQueryService meQueryService;

    @AuthenticatedOnly
    @AllowedWhenPending
    @Operation(summary = "본인 프로필 (C-14 자동 로그인 · 계정 상태 게이트 §1.4)")
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(meQueryService.getMe(authUser.accountId()));
    }
}
